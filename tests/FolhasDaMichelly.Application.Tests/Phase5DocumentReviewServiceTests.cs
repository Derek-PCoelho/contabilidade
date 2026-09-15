using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Tests;

public sealed class Phase5DocumentReviewServiceTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        "folhas-phase5-tests",
        Guid.NewGuid().ToString("N"));
    private readonly TestClock clock = new(new DateTimeOffset(2026, 8, 20, 12, 0, 0, TimeSpan.Zero));
    private readonly InMemoryReviewStore store = new();

    public Phase5DocumentReviewServiceTests()
    {
        Directory.CreateDirectory(temporaryDirectory);
    }

    [Fact]
    public void PeriodParserPreservesMonthlyAssessmentRangeEventAndDueDate()
    {
        var parser = new DocumentPeriodParser();

        var monthly = parser.Parse([
            Field("Competencia", "08/2026", SemanticFieldRole.Competence),
            Field("Vencimento", "2026-09-07", SemanticFieldRole.DueDate),
        ]);
        var assessment = parser.Parse([
            Field("Apuracao", "2026-08-31", SemanticFieldRole.AssessmentPeriod),
        ]);
        var range = parser.Parse([
            Field("Ferias", "01/09/2026 a 30/09/2026", SemanticFieldRole.VacationPeriod),
        ]);
        var eventDate = parser.Parse([
            Field("Desligamento", "2026-08-20", SemanticFieldRole.EventDate),
        ]);

        Assert.Equal(DocumentPeriodKind.Monthly, monthly.Kind);
        Assert.Equal("month:2026-08", monthly.CanonicalKey);
        Assert.Equal(new DateOnly(2026, 9, 7), monthly.DueDate);
        Assert.Equal(DocumentPeriodKind.AssessmentPeriod, assessment.Kind);
        Assert.Equal("2026-08", assessment.GroupingPeriodKey);
        Assert.Equal(DocumentPeriodKind.DateRange, range.Kind);
        Assert.Equal(new DateOnly(2026, 9, 30), range.EndDate);
        Assert.Equal(DocumentPeriodKind.EventDate, eventDate.Kind);
    }

    [Fact]
    public async Task PeriodCorrectionSurvivesRevalidationAndCanRestoreTheExtractedPeriod()
    {
        using var service = CreateService();
        var path = CreateFile("competencia-corrigida.pdf", "competencia corrigida sintetica");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll, competence: "08/2026"),
            CancellationToken.None);
        var originalGroupId = Assert.Single(workspace.Groups).Id;
        workspace = await service.ApproveGroupAsync(originalGroupId, CancellationToken.None);
        var documentId = Assert.Single(workspace.Documents).Id;
        var september = new DocumentPeriod(
            DocumentPeriodKind.Monthly,
            9,
            2026,
            null,
            null,
            null,
            "09/2026");

        workspace = await service.CorrectPeriodAsync(
            documentId,
            september,
            "Competência conferida manualmente no documento sintético.",
            CancellationToken.None);

        var corrected = Assert.Single(workspace.Documents);
        Assert.Equal(september, corrected.PeriodOverride);
        Assert.Equal("2026-09", corrected.Period.GroupingPeriodKey);
        var correctedGroup = Assert.Single(workspace.Groups);
        Assert.NotEqual(originalGroupId, correctedGroup.Id);
        Assert.Equal("2026-09", correctedGroup.PeriodKey);
        Assert.False(correctedGroup.IsApproved);
        Assert.Equal(correctedGroup.Id, corrected.GroupId);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "document.period_corrected");
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.approval_invalidated");
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "group.empty_removed" && audit.GroupId == originalGroupId);

        workspace = await service.LoadAsync(CancellationToken.None);
        corrected = Assert.Single(workspace.Documents);
        Assert.Equal(september, corrected.PeriodOverride);
        Assert.Equal("2026-09", corrected.Period.GroupingPeriodKey);

        workspace = await service.RestoreExtractedPeriodAsync(
            documentId,
            "Retorno ao período extraído após nova conferência sintética.",
            CancellationToken.None);

        var restored = Assert.Single(workspace.Documents);
        Assert.Null(restored.PeriodOverride);
        Assert.Equal("2026-08", restored.Period.GroupingPeriodKey);
        Assert.Equal("2026-08", Assert.Single(workspace.Groups).PeriodKey);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "document.period_restored");
    }

    [Fact]
    public async Task PeriodOverridePersistsInJsonAndLegacyPayloadWithoutItStillLoads()
    {
        using var service = CreateService();
        var path = CreateFile("competencia-json.pdf", "competencia json sintetica");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll, competence: "08/2026"),
            CancellationToken.None);
        var documentId = Assert.Single(workspace.Documents).Id;
        var correctedPeriod = new DocumentPeriod(
            DocumentPeriodKind.Monthly,
            9,
            2026,
            null,
            null,
            null,
            "09/2026");
        workspace = await service.CorrectPeriodAsync(
            documentId,
            correctedPeriod,
            "Correção sintética para persistência JSON.",
            CancellationToken.None);
        var corrected = Assert.Single(workspace.Documents);
        var jsonOptions = new JsonSerializerOptions(JsonSerializerDefaults.Web);

        var serialized = JsonSerializer.Serialize(corrected, jsonOptions);
        var roundTrip = JsonSerializer.Deserialize<ReviewDocument>(serialized, jsonOptions);

        Assert.NotNull(roundTrip);
        Assert.Equal(correctedPeriod, roundTrip.PeriodOverride);

        var legacyPayload = JsonNode.Parse(serialized)!.AsObject();
        Assert.True(legacyPayload.Remove("periodOverride"));
        var legacyRoundTrip = JsonSerializer.Deserialize<ReviewDocument>(legacyPayload.ToJsonString(), jsonOptions);

        Assert.NotNull(legacyRoundTrip);
        Assert.Null(legacyRoundTrip.PeriodOverride);
    }

    [Fact]
    public async Task RemovingDocumentsKeepsPdfFilesInvalidatesApprovalAndPrunesEmptyGroups()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var payrollPath = CreateFile("retirar-folha.pdf", "folha sintetica para retirada");
        var fgtsPath = CreateFile("retirar-fgts.pdf", "fgts sintetico para retirada");
        var workspace = await service.ImportAsync(
            payrollPath,
            Recognition(payrollPath, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        workspace = await service.ImportAsync(
            fgtsPath,
            Recognition(fgtsPath, clientId, RecognizedDocumentType.FgtsDigital),
            CancellationToken.None);
        var groupId = Assert.Single(workspace.Groups).Id;
        workspace = await service.ApproveGroupAsync(groupId, CancellationToken.None);
        var payrollId = workspace.Documents.Single(document => document.LocalPath == payrollPath).Id;
        var fgtsId = workspace.Documents.Single(document => document.LocalPath == fgtsPath).Id;

        workspace = await service.RemoveDocumentAsync(
            payrollId,
            "Documento importado por engano no teste sintético.",
            CancellationToken.None);

        Assert.True(File.Exists(payrollPath));
        Assert.True(File.Exists(fgtsPath));
        Assert.DoesNotContain(workspace.Documents, document => document.Id == payrollId);
        var remainingGroup = Assert.Single(workspace.Groups);
        Assert.Equal(groupId, remainingGroup.Id);
        Assert.False(remainingGroup.IsApproved);
        Assert.Equal([fgtsId], remainingGroup.DocumentIds);
        var removalAudit = Assert.Single(workspace.AuditEvents, audit =>
            audit.Action == "document.removed_from_review");
        Assert.Contains($"client:{clientId:N}", removalAudit.PreviousValue, StringComparison.Ordinal);
        Assert.Contains("client-name:", removalAudit.PreviousValue, StringComparison.Ordinal);
        Assert.Contains("type:Payroll", removalAudit.PreviousValue, StringComparison.Ordinal);
        Assert.Contains("period:month:2026-08", removalAudit.PreviousValue, StringComparison.Ordinal);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.approval_invalidated");

        workspace = await service.RemoveDocumentAsync(
            fgtsId,
            "Último documento retirado do grupo sintético incorreto.",
            CancellationToken.None);

        Assert.True(File.Exists(fgtsPath));
        Assert.Empty(workspace.Documents);
        Assert.Empty(workspace.Groups);
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "group.empty_removed" && audit.GroupId == groupId);
    }

    [Fact]
    public async Task LoadingAWorkspacePrunesLegacyEmptyGroupsAndInvalidatesTheirApproval()
    {
        using var service = CreateService();
        var path = CreateFile("grupo-vazio-legado.pdf", "grupo vazio legado sintetico");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var groupId = Assert.Single(workspace.Groups).Id;
        workspace = await service.ApproveGroupAsync(groupId, CancellationToken.None);
        var approvedGroup = Assert.Single(workspace.Groups);
        store.Seed(workspace with
        {
            Documents = [],
            Groups = [approvedGroup with { DocumentIds = [] }],
        });

        workspace = await service.LoadAsync(CancellationToken.None);

        Assert.Empty(workspace.Documents);
        Assert.Empty(workspace.Groups);
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "group.approval_invalidated" && audit.GroupId == groupId);
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "group.empty_removed" && audit.GroupId == groupId);
    }

    [Fact]
    public async Task InvalidPeriodCorrectionAndMissingOverrideNeverSavePartialChanges()
    {
        using var service = CreateService();
        var path = CreateFile("competencia-invalida.pdf", "competencia invalida sintetica");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var documentId = Assert.Single(workspace.Documents).Id;
        var savesBefore = store.SaveCount;

        var invalid = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.CorrectPeriodAsync(
                documentId,
                DocumentPeriod.Unknown("competência inválida"),
                "Correção sintética que deve ser rejeitada integralmente.",
                CancellationToken.None));
        Assert.Equal("period.correction_invalid", invalid.Code);
        Assert.Equal(savesBefore, store.SaveCount);

        var missing = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.RestoreExtractedPeriodAsync(
                documentId,
                "Tentativa sintética sem correção manual existente.",
                CancellationToken.None));
        Assert.Equal("period.override_not_found", missing.Code);
        Assert.Equal(savesBefore, store.SaveCount);
    }

    [Fact]
    public async Task ExactAndSemanticDuplicatesNeverCreateAnotherApprovalGroup()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var firstPath = CreateFile("primeiro.pdf", "conteudo sintetico um");
        var semanticPath = CreateFile("segunda-via.pdf", "conteudo sintetico dois");
        var first = Recognition(firstPath, clientId, RecognizedDocumentType.Payroll);

        var workspace = await service.ImportAsync(firstPath, first, CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(firstPath, first, CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            semanticPath,
            Recognition(semanticPath, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);

        Assert.Equal(3, workspace.Documents.Count);
        Assert.Single(workspace.Groups);
        Assert.Equal(2, workspace.Documents.Count(document => document.State == ReviewDocumentState.Duplicate));
        Assert.Contains(workspace.Documents, document =>
            document.Findings.Any(finding => finding.RuleCode == "duplicate.exact_sha256"));
        Assert.Contains(workspace.Documents, document =>
            document.Findings.Any(finding => finding.RuleCode == "duplicate.semantic_key"));

        workspace = await service.ApproveAllEligibleAsync(CancellationToken.None);

        Assert.Single(workspace.Groups, group => group.IsApproved);
        Assert.All(
            workspace.Documents.Where(document => document.State == ReviewDocumentState.Duplicate),
            document => Assert.Null(document.GroupId));
    }

    [Fact]
    public async Task FileChangeInvalidatesApprovalAndBlocksASecondApproval()
    {
        using var service = CreateService();
        var path = CreateFile("folha.pdf", "conteudo aprovado sintetico");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var groupId = Assert.Single(workspace.Groups).Id;
        workspace = await service.ApproveGroupAsync(groupId, CancellationToken.None);
        Assert.True(Assert.Single(workspace.Groups).IsApproved);

        await File.WriteAllTextAsync(path, "conteudo alterado depois da aprovacao");
        workspace = await service.RevalidateAsync(CancellationToken.None);

        var document = Assert.Single(workspace.Documents);
        Assert.Equal(ReviewDocumentState.Blocked, document.State);
        Assert.Contains(document.Findings, finding => finding.RuleCode == "document.hash_changed");
        Assert.Empty(workspace.Groups);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.approval_invalidated");
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "group.empty_removed" && audit.GroupId == groupId);
        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveGroupAsync(groupId, CancellationToken.None));
        Assert.Equal("group.not_found", exception.Code);
    }

    [Fact]
    public async Task RevalidationResolvesDocumentsAfterTheLocalClientCatalogBecomesAvailable()
    {
        var clientId = Guid.NewGuid();
        var resolver = new DeferredClientResolver(clientId);
        using var service = CreateService(resolver);
        var path = CreateFile("folha-a-resolver.pdf", "conteudo sintetico para resolver cliente");

        var workspace = await service.ImportAsync(
            path,
            Recognition(path, null, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        Assert.Null(Assert.Single(workspace.Documents).ClientId);

        resolver.CanResolve = true;
        workspace = await service.RevalidateAsync(CancellationToken.None);

        var resolved = Assert.Single(workspace.Documents);
        Assert.Equal(clientId, resolved.ClientId);
        Assert.Empty(resolved.ResolutionBlockers);
        Assert.NotEqual(ReviewDocumentState.Blocked, resolved.State);
    }

    [Fact]
    public async Task CatalogChangeInvalidatesApprovalAndNeverSilentlyMovesTheDocumentToAnotherClient()
    {
        var originalClientId = Guid.NewGuid();
        var replacementClientId = Guid.NewGuid();
        var resolver = new MutableClientResolver(Resolved(originalClientId, "Cliente original"));
        using var service = CreateService(resolver);
        var path = CreateFile("folha-catalogo-alterado.pdf", "conteudo sintetico com cliente alterado");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, originalClientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var groupId = Assert.Single(workspace.Groups).Id;
        workspace = await service.ApproveGroupAsync(groupId, CancellationToken.None);
        Assert.True(Assert.Single(workspace.Groups).IsApproved);

        resolver.Result = Resolved(replacementClientId, "Outro cliente");
        workspace = await service.RevalidateAsync(CancellationToken.None);

        var document = Assert.Single(workspace.Documents);
        Assert.Null(document.ClientId);
        Assert.Equal(ReviewDocumentState.Blocked, document.State);
        Assert.Contains("client.assignment_changed", document.ResolutionBlockers);
        Assert.Empty(workspace.Groups);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.approval_invalidated");
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.empty_removed");
    }

    [Fact]
    public async Task ManualClientConfirmationSurvivesRevalidationOnlyWhileCandidateRemainsValid()
    {
        var candidate = new ClientResolutionCandidate(
            Guid.NewGuid(),
            null,
            "Cliente conferido",
            "11.***.***/****-81",
            ClientResolutionMethod.FuzzySuggestion,
            .78m);
        var unresolved = new ClientResolutionResult(
            null,
            null,
            null,
            null,
            ClientResolutionMethod.None,
            0m,
            [],
            [candidate],
            ["client.not_resolved"]);
        var resolver = new MutableClientResolver(unresolved);
        using var service = CreateService(resolver);
        var path = CreateFile("folha-confirmacao-manual.pdf", "conteudo sintetico ambiguo");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, null, RecognizedDocumentType.Payroll, [candidate]),
            CancellationToken.None);
        var unresolvedDocument = Assert.Single(workspace.Documents);
        Assert.Single(unresolvedDocument.Findings, finding => finding.RuleCode == "client.not_resolved");
        Assert.DoesNotContain(unresolvedDocument.Findings, finding =>
            finding.Message.Contains("client.", StringComparison.Ordinal));
        var documentId = unresolvedDocument.Id;

        workspace = await service.OverrideClientAsync(
            documentId,
            candidate,
            "Cadastro e documento sintéticos conferidos pelo operador.",
            CancellationToken.None);
        workspace = await service.RevalidateAsync(CancellationToken.None);

        var document = Assert.Single(workspace.Documents);
        Assert.Equal(candidate.ClientId, document.ClientId);
        Assert.Equal(ClientResolutionMethod.ManualOverride, document.ResolutionMethod);
        Assert.Empty(document.ResolutionBlockers);
        Assert.Equal(ReviewDocumentState.Grouped, document.State);

        resolver.Result = ClientResolutionResult.Unresolved("client.inactive");
        workspace = await service.RevalidateAsync(CancellationToken.None);
        document = Assert.Single(workspace.Documents);
        Assert.Null(document.ClientId);
        Assert.Equal(ReviewDocumentState.Blocked, document.State);
    }

    [Fact]
    public async Task ClientNameChangeRefreshesTheReusedReviewGroup()
    {
        var clientId = Guid.NewGuid();
        var resolver = new MutableClientResolver(Resolved(clientId, "Nome anterior"));
        using var service = CreateService(resolver);
        var path = CreateFile("folha-nome-atualizado.pdf", "conteudo sintetico com nome atualizado");

        var workspace = await service.ImportAsync(
            path,
            Recognition(path, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var originalGroup = Assert.Single(workspace.Groups);
        Assert.Equal("Nome anterior", originalGroup.ClientDisplayName);

        resolver.Result = Resolved(clientId, "Nome atualizado");
        workspace = await service.RevalidateAsync(CancellationToken.None);

        var refreshedGroup = Assert.Single(workspace.Groups);
        Assert.Equal(originalGroup.Id, refreshedGroup.Id);
        Assert.Equal("Nome atualizado", refreshedGroup.ClientDisplayName);
        Assert.Equal(refreshedGroup.Id, Assert.Single(workspace.Documents).GroupId);
    }

    [Fact]
    public async Task SelectedBulkApprovalNeverApprovesAnUnrequestedGroup()
    {
        using var service = CreateService();
        var firstPath = CreateFile("cliente-selecionado.pdf", "cliente selecionado sintetico");
        var secondPath = CreateFile("cliente-nao-selecionado.pdf", "cliente nao selecionado sintetico");
        var workspace = await service.ImportAsync(
            firstPath,
            Recognition(firstPath, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            secondPath,
            Recognition(secondPath, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        Assert.Equal(2, workspace.Groups.Count);
        var selectedId = workspace.Groups[0].Id;
        var unrequestedId = workspace.Groups[1].Id;
        var savesBeforeApproval = store.SaveCount;

        workspace = await service.ApproveGroupsAsync([selectedId], CancellationToken.None);

        Assert.Equal(savesBeforeApproval + 1, store.SaveCount);
        Assert.True(workspace.Groups.Single(group => group.Id == selectedId).IsApproved);
        Assert.False(workspace.Groups.Single(group => group.Id == unrequestedId).IsApproved);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "groups.selection_approved");
    }

    [Fact]
    public async Task ClientApprovalKeepsThreeCompetencesSeparateAndApprovesAllFiveDocumentsAtomically()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var inputs = new[]
        {
            ("folha-agosto-cliente.pdf", "folha agosto cliente", RecognizedDocumentType.Payroll, "08/2026"),
            ("fgts-agosto-cliente.pdf", "fgts agosto cliente", RecognizedDocumentType.FgtsDigital, "08/2026"),
            ("prolabore-agosto-cliente.pdf", "prolabore agosto cliente", RecognizedDocumentType.ProLabore, "08/2026"),
            ("folha-setembro-cliente.pdf", "folha setembro cliente", RecognizedDocumentType.Payroll, "09/2026"),
            ("folha-dezembro-cliente.pdf", "folha dezembro cliente", RecognizedDocumentType.Payroll, "12/2026"),
        };
        DocumentReviewWorkspace? workspace = null;
        foreach (var (fileName, content, documentType, competence) in inputs)
        {
            var path = CreateFile(fileName, content);
            workspace = await service.ImportAsync(
                path,
                Recognition(path, clientId, documentType, competence: competence),
                CancellationToken.None);
            clock.Advance();
        }

        Assert.NotNull(workspace);
        Assert.Equal(5, workspace.Documents.Count);
        Assert.Equal(3, workspace.Groups.Count);
        Assert.Equal([1, 1, 3], workspace.Groups.Select(group => group.DocumentIds.Count).Order());
        Assert.Equal(3, workspace.Groups.Select(group => group.PeriodKey).Distinct().Count());
        var originalMemberships = workspace.Documents.ToDictionary(
            document => document.Id,
            document => document.GroupId);
        var groupIds = workspace.Groups.Select(group => group.Id).ToArray();
        var savesBeforeApproval = store.SaveCount;

        workspace = await service.ApproveClientGroupsAsync(
            clientId,
            groupIds,
            CancellationToken.None);

        Assert.Equal(savesBeforeApproval + 1, store.SaveCount);
        Assert.Equal(3, workspace.Groups.Count);
        Assert.All(workspace.Groups, group => Assert.True(group.IsApproved));
        Assert.All(workspace.Documents, document =>
        {
            Assert.Equal(ReviewDocumentState.Approved, document.State);
            Assert.Equal(originalMemberships[document.Id], document.GroupId);
        });
        var audit = Assert.Single(
            workspace.AuditEvents,
            item => item.Action == "groups.client_approved");
        Assert.Equal("synthetic-operator", audit.ActorId);
        Assert.StartsWith(
            $"client:{clientId:N};groups:3;documents:5;competences:3;periods:",
            audit.NewValue,
            StringComparison.Ordinal);
        Assert.Contains("08/2026", audit.NewValue, StringComparison.Ordinal);
        Assert.Contains("09/2026", audit.NewValue, StringComparison.Ordinal);
        Assert.Contains("12/2026", audit.NewValue, StringComparison.Ordinal);
        Assert.Contains("Aprovação humana", audit.Reason, StringComparison.Ordinal);
    }

    [Fact]
    public async Task ClientApprovalRejectsAGroupFromAnotherClientWithoutSavingAnything()
    {
        using var service = CreateService();
        var selectedClientId = Guid.NewGuid();
        var otherClientId = Guid.NewGuid();
        var selectedPath = CreateFile("cliente-liberado.pdf", "cliente liberado sintetico");
        var otherPath = CreateFile("cliente-divergente.pdf", "cliente divergente sintetico");
        var workspace = await service.ImportAsync(
            selectedPath,
            Recognition(selectedPath, selectedClientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            otherPath,
            Recognition(otherPath, otherClientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var savesBeforeApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveClientGroupsAsync(
                selectedClientId,
                workspace.Groups.Select(group => group.Id).ToArray(),
                CancellationToken.None));

        Assert.Equal("group.client_mismatch", exception.Code);
        Assert.Equal(savesBeforeApproval, store.SaveCount);
        workspace = await service.LoadAsync(CancellationToken.None);
        Assert.All(workspace.Groups, group => Assert.False(group.IsApproved));
        Assert.DoesNotContain(workspace.AuditEvents, audit => audit.Action == "groups.client_approved");
    }

    [Fact]
    public async Task ClientApprovalRequiresEveryApprovableGroupAndDoesNotPartiallySave()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var augustPath = CreateFile("cliente-completo-agosto.pdf", "cliente completo agosto");
        var septemberPath = CreateFile("cliente-completo-setembro.pdf", "cliente completo setembro");
        var workspace = await service.ImportAsync(
            augustPath,
            Recognition(
                augustPath,
                clientId,
                RecognizedDocumentType.Payroll,
                competence: "08/2026"),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            septemberPath,
            Recognition(
                septemberPath,
                clientId,
                RecognizedDocumentType.Payroll,
                competence: "09/2026"),
            CancellationToken.None);
        var savesBeforeApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveClientGroupsAsync(
                clientId,
                [workspace.Groups[0].Id],
                CancellationToken.None));

        Assert.Equal("groups.client_selection_incomplete", exception.Code);
        Assert.Equal(savesBeforeApproval, store.SaveCount);
        workspace = await service.LoadAsync(CancellationToken.None);
        Assert.All(workspace.Groups, group => Assert.False(group.IsApproved));
        Assert.DoesNotContain(workspace.AuditEvents, audit => audit.Action == "groups.client_approved");
    }

    [Fact]
    public async Task ClientApprovalRejectsANonApprovableRequestedGroupWithoutApprovingTheOthers()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var augustPath = CreateFile("cliente-bloqueado-agosto.pdf", "cliente bloqueado agosto");
        var septemberPath = CreateFile("cliente-bloqueado-setembro.pdf", "cliente bloqueado setembro");
        var workspace = await service.ImportAsync(
            augustPath,
            Recognition(
                augustPath,
                clientId,
                RecognizedDocumentType.Payroll,
                competence: "08/2026"),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            septemberPath,
            Recognition(
                septemberPath,
                clientId,
                RecognizedDocumentType.Payroll,
                competence: "09/2026"),
            CancellationToken.None);
        var augustGroup = workspace.Groups.Single(group => group.PeriodKey == "2026-08");
        workspace = await service.ApproveGroupAsync(augustGroup.Id, CancellationToken.None);
        var savesBeforeClientApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveClientGroupsAsync(
                clientId,
                workspace.Groups.Select(group => group.Id).ToArray(),
                CancellationToken.None));

        Assert.Equal("group.approval_blocked", exception.Code);
        Assert.Equal(savesBeforeClientApproval, store.SaveCount);
        workspace = await service.LoadAsync(CancellationToken.None);
        Assert.True(workspace.Groups.Single(group => group.Id == augustGroup.Id).IsApproved);
        Assert.False(workspace.Groups.Single(group => group.Id != augustGroup.Id).IsApproved);
        Assert.DoesNotContain(workspace.AuditEvents, audit => audit.Action == "groups.client_approved");
    }

    [Fact]
    public async Task BulkApprovalAcceptsDifferentPeriodKindsInsideTheSameCalendarMonth()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var monthlyPath = CreateFile("folha-agosto.pdf", "folha agosto sintetica");
        var vacationPath = CreateFile("ferias-agosto.pdf", "ferias agosto sinteticas");
        var workspace = await service.ImportAsync(
            monthlyPath,
            Recognition(
                monthlyPath,
                clientId,
                RecognizedDocumentType.Payroll,
                competence: "08/2026"),
            CancellationToken.None);
        clock.Advance();
        var vacationRecognition = Recognition(
            vacationPath,
            clientId,
            RecognizedDocumentType.Vacation) with
        {
            Fields =
            [
                Field("EmpregadorCnpj", "11222333000181", SemanticFieldRole.EmployerTaxId),
                Field("Ferias", "05/08/2026 a 20/08/2026", SemanticFieldRole.VacationPeriod),
                Field("ValorTotal", "1234.56", SemanticFieldRole.TotalAmount),
            ],
        };
        workspace = await service.ImportAsync(
            vacationPath,
            vacationRecognition,
            CancellationToken.None);

        Assert.Equal(2, workspace.Groups.Count);
        Assert.Equal(2, workspace.Groups.Select(group => group.PeriodKey).Distinct().Count());

        workspace = await service.ApproveAllEligibleAsync(CancellationToken.None);

        Assert.All(workspace.Groups, group => Assert.True(group.IsApproved));
        Assert.Contains(workspace.AuditEvents, audit =>
            audit.Action == "groups.bulk_approved" && audit.NewValue == "approved:2");
    }

    [Fact]
    public async Task BulkApprovalStillRejectsEligibleGroupsFromDifferentCalendarMonths()
    {
        using var service = CreateService();
        var augustPath = CreateFile("todos-agosto.pdf", "todos agosto sintetico");
        var septemberPath = CreateFile("todos-setembro.pdf", "todos setembro sintetico");
        var workspace = await service.ImportAsync(
            augustPath,
            Recognition(
                augustPath,
                Guid.NewGuid(),
                RecognizedDocumentType.Payroll,
                competence: "08/2026"),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            septemberPath,
            Recognition(
                septemberPath,
                Guid.NewGuid(),
                RecognizedDocumentType.Payroll,
                competence: "09/2026"),
            CancellationToken.None);
        Assert.Equal(2, workspace.Groups.Count);
        var savesBeforeApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveAllEligibleAsync(CancellationToken.None));

        Assert.Equal("groups.period_mismatch", exception.Code);
        Assert.Equal(savesBeforeApproval, store.SaveCount);
        workspace = await service.LoadAsync(CancellationToken.None);
        Assert.All(workspace.Groups, group => Assert.False(group.IsApproved));
        Assert.DoesNotContain(workspace.AuditEvents, audit => audit.Action == "groups.bulk_approved");
    }

    [Fact]
    public async Task SelectedBulkApprovalIsAtomicWhenAnyRequestedGroupIsInvalid()
    {
        using var service = CreateService();
        var path = CreateFile("lote-atomico.pdf", "lote atomico sintetico");
        var workspace = await service.ImportAsync(
            path,
            Recognition(path, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        var validGroupId = Assert.Single(workspace.Groups).Id;
        var savesBeforeApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveGroupsAsync(
                [validGroupId, Guid.NewGuid()],
                CancellationToken.None));
        Assert.Equal("group.not_found", exception.Code);
        Assert.Equal(savesBeforeApproval, store.SaveCount);

        workspace = await service.LoadAsync(CancellationToken.None);
        Assert.False(Assert.Single(workspace.Groups).IsApproved);
        Assert.DoesNotContain(workspace.AuditEvents, audit => audit.Action == "groups.selection_approved");
    }

    [Fact]
    public async Task SelectedBulkApprovalRejectsMixedPeriodsWithoutSaving()
    {
        using var service = CreateService();
        var augustPath = CreateFile("lote-agosto.pdf", "lote agosto sintetico");
        var septemberPath = CreateFile("lote-setembro.pdf", "lote setembro sintetico");
        var workspace = await service.ImportAsync(
            augustPath,
            Recognition(
                augustPath,
                Guid.NewGuid(),
                RecognizedDocumentType.Payroll,
                competence: "08/2026"),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            septemberPath,
            Recognition(
                septemberPath,
                Guid.NewGuid(),
                RecognizedDocumentType.Payroll,
                competence: "09/2026"),
            CancellationToken.None);
        Assert.Equal(2, workspace.Groups.Count);
        var savesBeforeApproval = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.ApproveGroupsAsync(
                workspace.Groups.Select(group => group.Id).ToArray(),
                CancellationToken.None));

        Assert.Equal("groups.period_mismatch", exception.Code);
        Assert.Equal(savesBeforeApproval, store.SaveCount);
        Assert.All(workspace.Groups, group => Assert.False(group.IsApproved));
    }

    [Fact]
    public async Task SplitAndMergeRevokeSnapshotsAndKeepAnAuditTrail()
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var payrollPath = CreateFile("folha.pdf", "folha sintetica");
        var fgtsPath = CreateFile("fgts.pdf", "fgts sintetico");
        var workspace = await service.ImportAsync(
            payrollPath,
            Recognition(payrollPath, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            fgtsPath,
            Recognition(fgtsPath, clientId, RecognizedDocumentType.FgtsDigital),
            CancellationToken.None);
        var group = Assert.Single(workspace.Groups);
        Assert.Equal(2, group.DocumentIds.Count);
        workspace = await service.ApproveGroupAsync(group.Id, CancellationToken.None);

        workspace = await service.SplitGroupAsync(
            group.Id,
            [group.DocumentIds[0]],
            "Separação solicitada para conferência contábil.",
            CancellationToken.None);

        Assert.Equal(2, workspace.Groups.Count);
        Assert.All(workspace.Groups, current => Assert.False(current.IsApproved));
        var target = workspace.Groups[0];
        var source = workspace.Groups[1];
        workspace = await service.ApproveGroupAsync(target.Id, CancellationToken.None);
        workspace = await service.MergeGroupsAsync(
            target.Id,
            source.Id,
            "Reunião confirmada após conferência do período.",
            CancellationToken.None);

        var merged = Assert.Single(workspace.Groups);
        Assert.False(merged.IsApproved);
        Assert.Equal(2, merged.DocumentIds.Count);
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.split");
        Assert.Contains(workspace.AuditEvents, audit => audit.Action == "group.merged");
        Assert.True(workspace.AuditEvents.Count(audit => audit.Action == "group.approval_invalidated") >= 2);
    }

    [Fact]
    public async Task CrossClientMergeIsRejected()
    {
        using var service = CreateService();
        var firstPath = CreateFile("cliente-a.pdf", "cliente a sintetico");
        var secondPath = CreateFile("cliente-b.pdf", "cliente b sintetico");
        var workspace = await service.ImportAsync(
            firstPath,
            Recognition(firstPath, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            secondPath,
            Recognition(secondPath, Guid.NewGuid(), RecognizedDocumentType.Payroll),
            CancellationToken.None);
        Assert.Equal(2, workspace.Groups.Count);

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() => service.MergeGroupsAsync(
            workspace.Groups[0].Id,
            workspace.Groups[1].Id,
            "Tentativa sintética entre clientes diferentes.",
            CancellationToken.None));

        Assert.Equal("group.client_mismatch", exception.Code);
    }

    [Theory]
    [InlineData("establishment", "group.establishment_mismatch")]
    [InlineData("policy-code", "group.policy_mismatch")]
    [InlineData("policy-version", "group.policy_mismatch")]
    public async Task ManualMergeRejectsAccountingIncompatibilitiesWithoutSaving(
        string incompatibility,
        string expectedCode)
    {
        using var service = CreateService();
        var clientId = Guid.NewGuid();
        var payrollPath = CreateFile($"folha-{incompatibility}.pdf", "folha sintetica");
        var fgtsPath = CreateFile($"fgts-{incompatibility}.pdf", "fgts sintetico");
        var workspace = await service.ImportAsync(
            payrollPath,
            Recognition(payrollPath, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            fgtsPath,
            Recognition(fgtsPath, clientId, RecognizedDocumentType.FgtsDigital),
            CancellationToken.None);
        var originalGroup = Assert.Single(workspace.Groups);
        workspace = await service.SplitGroupAsync(
            originalGroup.Id,
            [originalGroup.DocumentIds[0]],
            "Separação sintética para validar compatibilidade.",
            CancellationToken.None);
        Assert.Equal(2, workspace.Groups.Count);

        var target = workspace.Groups[0];
        var source = workspace.Groups[1];
        var incompatibleSource = incompatibility switch
        {
            "establishment" => source with { EstablishmentId = Guid.NewGuid() },
            "policy-code" => source with { GroupingPolicyCode = "outra-politica" },
            "policy-version" => source with { GroupingPolicyVersion = "outra-versao" },
            _ => throw new InvalidOperationException("Incompatibilidade sintética desconhecida."),
        };
        store.Seed(workspace with
        {
            Groups = workspace.Groups
                .Select(group => group.Id == source.Id ? incompatibleSource : group)
                .ToArray(),
        });
        var savesBeforeMerge = store.SaveCount;

        var exception = await Assert.ThrowsAsync<DocumentReviewException>(() => service.MergeGroupsAsync(
            target.Id,
            source.Id,
            "Tentativa sintética de união incompatível.",
            CancellationToken.None));

        Assert.Equal(expectedCode, exception.Code);
        Assert.Equal(savesBeforeMerge, store.SaveCount);
        var persisted = await service.LoadAsync(CancellationToken.None);
        Assert.Equal(2, persisted.Groups.Count);
        Assert.DoesNotContain(persisted.AuditEvents, audit => audit.Action == "group.merged");
    }

    [Fact]
    public async Task ClientOverrideRequiresReasonAndAuthenticatedAlternative()
    {
        using var service = CreateService();
        var candidate = new ClientResolutionCandidate(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "Cliente sintético alternativo",
            "11.***.***/****-81",
            ClientResolutionMethod.FuzzySuggestion,
            .75m);
        var path = CreateFile("ambiguo.pdf", "conteudo ambiguo sintetico");
        var recognition = Recognition(path, null, RecognizedDocumentType.Payroll, [candidate]);
        var workspace = await service.ImportAsync(path, recognition, CancellationToken.None);
        var document = Assert.Single(workspace.Documents);
        Assert.Equal(ReviewDocumentState.Blocked, document.State);

        var reasonException = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.OverrideClientAsync(document.Id, candidate, "curto", CancellationToken.None));
        Assert.Equal("client.override_reason_required", reasonException.Code);
        var forged = candidate with { ClientId = Guid.NewGuid() };
        var candidateException = await Assert.ThrowsAsync<DocumentReviewException>(() =>
            service.OverrideClientAsync(
                document.Id,
                forged,
                "Cliente conferido em cadastro externo sintético.",
                CancellationToken.None));
        Assert.Equal("client.override_candidate_not_trusted", candidateException.Code);

        workspace = await service.OverrideClientAsync(
            document.Id,
            candidate,
            "Identificador e razão social conferidos pelo operador.",
            CancellationToken.None);

        document = Assert.Single(workspace.Documents);
        Assert.Equal(candidate.ClientId, document.ClientId);
        Assert.Equal(ReviewDocumentState.Grouped, document.State);
        var audit = Assert.Single(
            workspace.AuditEvents,
            item => item.Action == "document.client_overridden");
        Assert.Contains("Não resolvido", audit.PreviousValue, StringComparison.Ordinal);
        Assert.Equal("synthetic-operator", audit.ActorId);
        Assert.NotNull(audit.Reason);
    }

    [Fact]
    public async Task ErrorSeverityIsAlsoExcludedFromBulkApproval()
    {
        using var service = CreateService();
        var goodPath = CreateFile("valido.pdf", "valor positivo");
        var zeroPath = CreateFile("zero.pdf", "valor zero");
        var clientId = Guid.NewGuid();
        var workspace = await service.ImportAsync(
            goodPath,
            Recognition(goodPath, clientId, RecognizedDocumentType.Payroll),
            CancellationToken.None);
        clock.Advance();
        workspace = await service.ImportAsync(
            zeroPath,
            Recognition(zeroPath, clientId, RecognizedDocumentType.ProLabore, amount: "0.00"),
            CancellationToken.None);
        workspace = await service.ApproveAllEligibleAsync(CancellationToken.None);

        Assert.Single(workspace.Groups);
        Assert.True(Assert.Single(workspace.Groups).IsApproved);
        var zero = workspace.Documents.Single(document => document.FileName == "zero.pdf");
        Assert.Equal(ReviewDocumentState.Blocked, zero.State);
        Assert.Contains(zero.Findings, finding =>
            finding.RuleCode == "amount.zero_unexpected" &&
            finding.Severity == ValidationSeverity.Error &&
            finding.PreventsApproval);
    }

    [Fact]
    public async Task DifferentEmployerCnpjRootsAreAnAccountingBlocker()
    {
        using var service = CreateService();
        var path = CreateFile("decimo-terceiro-duas-raizes.pdf", "duas raízes sintéticas");
        var recognition = Recognition(path, Guid.NewGuid(), RecognizedDocumentType.ThirteenthSalary);
        recognition = recognition with
        {
            Fields = recognition.Fields.Append(Field(
                "EmpregadorCnpj2",
                "99888777000166",
                SemanticFieldRole.EmployerTaxId)).ToArray(),
        };

        var workspace = await service.ImportAsync(path, recognition, CancellationToken.None);

        var document = Assert.Single(workspace.Documents);
        Assert.Equal(ReviewDocumentState.Blocked, document.State);
        Assert.Contains(document.Findings, finding =>
            finding.RuleCode == "client.multiple_employer_roots" &&
            finding.Severity == ValidationSeverity.Blocker);
        Assert.Empty(workspace.Groups);
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, true);
        }

        GC.SuppressFinalize(this);
    }

    private DocumentReviewService CreateService(IClientResolver? clientResolver = null)
    {
        var reviewOptions = new DocumentReviewOptions();
        IValidationRule<DocumentValidationContext>[] rules =
        [
            new FileIntegrityValidationRule(),
            new RecognitionValidationRule(),
            new ClientResolutionValidationRule(),
            new ProfileRequiredFieldsValidationRule(),
            new PeriodValidationRule(),
            new AmountValidationRule(),
            new EmployerRootConsistencyValidationRule(),
            new DueDateValidationRule(reviewOptions),
        ];
        return new DocumentReviewService(
            store,
            new FixedReviewContextAccessor(),
            new DocumentPeriodParser(),
            rules,
            reviewOptions,
            clock,
            clientResolver);
    }

    private string CreateFile(string fileName, string content)
    {
        var path = Path.Combine(temporaryDirectory, fileName);
        File.WriteAllText(path, content);
        return path;
    }

    private static DocumentRecognitionResult Recognition(
        string path,
        Guid? clientId,
        RecognizedDocumentType documentType,
        IReadOnlyList<ClientResolutionCandidate>? alternatives = null,
        string amount = "1234.56",
        string competence = "08/2026")
    {
        var fields = new List<RecognizedField>
        {
            Field("EmpregadorCnpj", "11222333000181", SemanticFieldRole.EmployerTaxId),
            Field("Competencia", competence, SemanticFieldRole.Competence),
            Field("ValorTotal", amount, SemanticFieldRole.TotalAmount),
        };
        var resolution = clientId.HasValue
            ? new ClientResolutionResult(
                clientId,
                null,
                $"Cliente sintético {clientId.Value:N}",
                "11.***.***/****-81",
                ClientResolutionMethod.ExactClientTaxId,
                .99m,
                [],
                [],
                [])
            : new ClientResolutionResult(
                null,
                null,
                null,
                null,
                ClientResolutionMethod.None,
                0m,
                [],
                alternatives ?? [],
                ["client.not_resolved"]);
        return new DocumentRecognitionResult(
            Path.GetFileName(path),
            Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(path))),
            "application/pdf",
            new FileInfo(path).Length,
            1,
            documentType,
            "synthetic-v1",
            RecognitionConfidence.High,
            .99m,
            false,
            false,
            fields,
            resolution,
            []);
    }

    private static RecognizedField Field(string name, string value, SemanticFieldRole role) => new(
        name,
        value,
        value,
        role,
        .99m,
        new EvidenceBox(1, 0, 0, 10, 10, $"{name}: sintético"));

    private static ClientResolutionResult Resolved(Guid clientId, string displayName) => new(
        clientId,
        null,
        displayName,
        "11.***.***/****-81",
        ClientResolutionMethod.ExactClientTaxId,
        .99m,
        [],
        [],
        []);

    private sealed class FixedReviewContextAccessor : IDocumentReviewContextAccessor
    {
        public Task<DocumentReviewContext> GetCurrentAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(new DocumentReviewContext("synthetic-scope", "synthetic-operator"));
        }
    }

    private sealed class DeferredClientResolver(Guid clientId) : IClientResolver
    {
        public bool CanResolve { get; set; }

        public Task<ClientResolutionResult> ResolveAsync(
            ClientResolutionRequest request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var result = CanResolve
                ? new ClientResolutionResult(
                    clientId,
                    null,
                    "Cliente sintético local",
                    "11.***.***/****-81",
                    ClientResolutionMethod.ExactClientTaxId,
                    .99m,
                    request.Fields.Select(field => field.Evidence).ToArray(),
                    [],
                    [])
                : ClientResolutionResult.Unresolved("client.resolution_offline");
            return Task.FromResult(result);
        }
    }

    private sealed class MutableClientResolver(ClientResolutionResult result) : IClientResolver
    {
        public ClientResolutionResult Result { get; set; } = result;

        public Task<ClientResolutionResult> ResolveAsync(
            ClientResolutionRequest request,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            return Task.FromResult(Result);
        }
    }

    private sealed class InMemoryReviewStore : IDocumentReviewStore
    {
        private DocumentReviewWorkspace workspace = DocumentReviewWorkspace.Empty("synthetic-scope");

        public int SaveCount { get; private set; }

        public void Seed(DocumentReviewWorkspace seeded) => workspace = seeded;

        public Task<DocumentReviewWorkspace> LoadAsync(
            string scopeKey,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Assert.Equal(workspace.ScopeKey, scopeKey);
            return Task.FromResult(workspace);
        }

        public Task SaveAsync(
            DocumentReviewWorkspace updated,
            CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            SaveCount++;
            workspace = updated;
            return Task.CompletedTask;
        }
    }

    private sealed class TestClock(DateTimeOffset now) : IClock
    {
        public DateTimeOffset UtcNow { get; private set; } = now;

        public void Advance() => UtcNow = UtcNow.AddMinutes(1);
    }
}
