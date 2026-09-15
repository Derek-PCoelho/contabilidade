using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Preferences;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Contracts.Identity;
using FolhasDaMichelly.Infrastructure.Documents;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using FolhasDaMichelly.Infrastructure.Security;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase5DocumentReviewPersistenceTests
{
    [Fact]
    public async Task WorkspaceFoldersPeriodAndSessionPreferenceRecoverAfterRestart()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        await using (var context = new LocalCacheDbContext(options))
        {
            await context.Database.EnsureCreatedAsync();
            var store = new SqliteWorkspacePreferencesStore(context);
            await store.SaveAsync(
                new WorkspacePreferences(
                    "/entrada/sintetica",
                    "/acervo/sintetico",
                    "/relatorios/sinteticos",
                    true,
                    2026,
                    8,
                    false),
                CancellationToken.None);
        }

        await using (var restarted = new LocalCacheDbContext(options))
        {
            var recovered = await new SqliteWorkspacePreferencesStore(restarted)
                .LoadAsync(CancellationToken.None);

            Assert.NotNull(recovered);
            Assert.Equal("/entrada/sintetica", recovered.InputFolderPath);
            Assert.Equal("/acervo/sintetico", recovered.DocumentArchiveDirectory);
            Assert.Equal("/relatorios/sinteticos", recovered.ReportOutputDirectory);
            Assert.Equal(2026, recovered.SelectedYear);
            Assert.Equal(8, recovered.SelectedMonth);
            Assert.False(recovered.KeepEmailSession);
        }
    }

    [Fact]
    public async Task ApprovedWorkspaceAndAppendOnlyAuditRecoverAfterRestart()
    {
        var filePath = Path.Combine(Path.GetTempPath(), $"folhas-review-{Guid.NewGuid():N}.pdf");
        await File.WriteAllTextAsync(filePath, "PDF sintético para persistência da fase cinco.");
        try
        {
            await using var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var dbOptions = new DbContextOptionsBuilder<LocalCacheDbContext>()
                .UseSqlite(connection)
                .Options;
            await using (var firstContext = new LocalCacheDbContext(dbOptions))
            {
                await firstContext.Database.EnsureCreatedAsync();
                using var service = CreateService(
                    new SqliteDocumentReviewStore(firstContext),
                    new FixedReviewContextAccessor("organization-a"));
                var workspace = await service.ImportAsync(
                    filePath,
                    Recognition(filePath),
                    CancellationToken.None);
                workspace = await service.ApproveGroupAsync(
                    Assert.Single(workspace.Groups).Id,
                    CancellationToken.None);
                Assert.True(Assert.Single(workspace.Groups).IsApproved);
            }

            await using (var restartedContext = new LocalCacheDbContext(dbOptions))
            {
                using var restarted = CreateService(
                    new SqliteDocumentReviewStore(restartedContext),
                    new FixedReviewContextAccessor("organization-a"));
                var recovered = await restarted.LoadAsync(CancellationToken.None);

                Assert.Single(recovered.Documents);
                Assert.True(Assert.Single(recovered.Groups).IsApproved);
                Assert.Contains(recovered.AuditEvents, audit => audit.Action == "group.approved");
                Assert.All(recovered.AuditEvents, audit => Assert.Equal("organization-a", audit.ScopeKey));
            }

            await using (var isolatedContext = new LocalCacheDbContext(dbOptions))
            {
                using var isolated = CreateService(
                    new SqliteDocumentReviewStore(isolatedContext),
                    new FixedReviewContextAccessor("organization-b"));
                var otherOrganization = await isolated.LoadAsync(CancellationToken.None);

                Assert.Empty(otherOrganization.Documents);
                Assert.Empty(otherOrganization.Groups);
                Assert.Empty(otherOrganization.AuditEvents);
            }
        }
        finally
        {
            File.Delete(filePath);
        }
    }

    [Fact]
    public async Task LocalWorkspaceScopeComesFromOrganizationClaimNotUserInput()
    {
        var organizationId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var secrets = new InMemorySecretStore();
        var token = CreateUnsignedTestToken(organizationId, userId);
        await secrets.StoreAsync("app-session/access-token", token, CancellationToken.None);
        var accessor = new JwtDocumentReviewContextAccessor(secrets);

        var context = await accessor.GetCurrentAsync(CancellationToken.None);

        Assert.Equal(organizationId.ToString("N"), context.ScopeKey);
        Assert.Equal(userId.ToString("D"), context.ActorId);
        Assert.DoesNotContain(token, context.ScopeKey, StringComparison.Ordinal);
    }

    [Fact]
    public void ThirteenthParserDeduplicatesHeadersButPreservesDistinctEmployerRoots()
    {
        const string firstPage = """
            RECIBO DE DECIMO TERCEIRO SALARIO
            EMPREGADOR CNPJ: 11.222.333/0001-81
            EMPREGADOR: CLIENTE SINTETICO
            EMPREGADO CPF: 529.982.247-25
            COMPETENCIA: 12/2026
            VALOR LIQUIDO: R$ 1.234,56
            """;
        const string secondPage = """
            EMPREGADOR CNPJ: 11.222.333/0001-81
            EMPREGADOR CNPJ: 99.888.777/0001-66
            """;
        var extraction = new PdfTextExtraction(
            2,
            [
                new ExtractedPdfPage(1, firstPage, []),
                new ExtractedPdfPage(2, secondPage, []),
            ]);
        var parser = new ProfileDocumentParser();

        var parsed = parser.Parse(
            extraction,
            new DocumentClassification(
                RecognizedDocumentType.ThirteenthSalary,
                "synthetic-v1",
                1m,
                ["DECIMO TERCEIRO SALARIO"]));

        var employers = parsed.Fields
            .Where(field => field.Role == SemanticFieldRole.EmployerTaxId)
            .Select(field => field.Value)
            .ToArray();
        Assert.Equal(["11222333000181", "99888777000166"], employers);
    }

    private static DocumentReviewService CreateService(
        IDocumentReviewStore store,
        IDocumentReviewContextAccessor contextAccessor)
    {
        var options = new DocumentReviewOptions();
        IValidationRule<DocumentValidationContext>[] rules =
        [
            new FileIntegrityValidationRule(),
            new RecognitionValidationRule(),
            new ClientResolutionValidationRule(),
            new ProfileRequiredFieldsValidationRule(),
            new PeriodValidationRule(),
            new AmountValidationRule(),
            new EmployerRootConsistencyValidationRule(),
            new DueDateValidationRule(options),
        ];
        return new DocumentReviewService(
            store,
            contextAccessor,
            new DocumentPeriodParser(),
            rules,
            options,
            new FixedClock());
    }

    private static DocumentRecognitionResult Recognition(string path)
    {
        var clientId = Guid.NewGuid();
        var fields = new RecognizedField[]
        {
            Field("EmpregadorCnpj", "11222333000181", SemanticFieldRole.EmployerTaxId),
            Field("Competencia", "08/2026", SemanticFieldRole.Competence),
            Field("ValorTotal", "9876.54", SemanticFieldRole.TotalAmount),
        };
        return new DocumentRecognitionResult(
            Path.GetFileName(path),
            Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))),
            "application/pdf",
            new FileInfo(path).Length,
            1,
            RecognizedDocumentType.Payroll,
            "synthetic-v1",
            RecognitionConfidence.High,
            .99m,
            false,
            false,
            fields,
            new ClientResolutionResult(
                clientId,
                null,
                "Cliente sintético persistido",
                "11.***.***/****-81",
                ClientResolutionMethod.ExactClientTaxId,
                .99m,
                [],
                [],
                []),
            []);
    }

    private static RecognizedField Field(string name, string value, SemanticFieldRole role) => new(
        name,
        value,
        value,
        role,
        .99m,
        new EvidenceBox(1, 0, 0, 10, 10, $"{name}: sintético"));

    private static string CreateUnsignedTestToken(Guid organizationId, Guid userId)
    {
        var header = Base64Url(JsonSerializer.SerializeToUtf8Bytes(new { alg = "none", typ = "JWT" }));
        var payload = Base64Url(JsonSerializer.SerializeToUtf8Bytes(new Dictionary<string, string>
        {
            [AppClaimNames.OrganizationId] = organizationId.ToString("D"),
            ["sub"] = userId.ToString("D"),
        }));
        return $"{header}.{payload}.synthetic";
    }

    private static string Base64Url(byte[] value) =>
        Convert.ToBase64String(value).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private sealed class FixedReviewContextAccessor(string scopeKey) : IDocumentReviewContextAccessor
    {
        public Task<DocumentReviewContext> GetCurrentAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(new DocumentReviewContext(scopeKey, "synthetic-operator"));
        }
    }

    private sealed class FixedClock : IClock
    {
        public DateTimeOffset UtcNow => new(2026, 8, 20, 12, 0, 0, TimeSpan.Zero);
    }
}
