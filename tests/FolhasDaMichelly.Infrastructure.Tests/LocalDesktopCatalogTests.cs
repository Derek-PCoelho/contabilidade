using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Pilot;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Clients;
using FolhasDaMichelly.Infrastructure.Documents;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using FolhasDaMichelly.Infrastructure.Security;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class LocalDesktopCatalogTests
{
    private static readonly DateTimeOffset Timestamp = new(2026, 8, 22, 15, 0, 0, TimeSpan.Zero);
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    [Fact]
    public async Task LocalCatalogSavesSearchesLoadsAndDeactivatesClient()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);

        Assert.Equal(1, saved.Version);
        Assert.Equal("11222333000181", saved.PrimaryTaxId);
        Assert.Equal("financeiro@example.com", Assert.Single(saved.Recipients).Email);
        Assert.Equal("socia@example.com", Assert.Single(saved.Partners!).Email);

        var search = await harness.Catalog.SearchAsync(
            "11.222.333",
            true,
            PersonTypeModel.LegalEntity,
            CancellationToken.None);
        Assert.Equal(saved.Id, Assert.Single(search.Items).Id);
        var loaded = await harness.Catalog.GetAsync(saved.Id, CancellationToken.None);
        Assert.NotNull(loaded);
        Assert.Equal(saved.Id, loaded.Id);
        Assert.Equal(saved.PrimaryTaxId, loaded.PrimaryTaxId);
        Assert.Equal(saved.Recipients, loaded.Recipients);

        var readiness = await harness.Catalog.GetReadinessAsync(saved.Id, CancellationToken.None);
        Assert.NotNull(readiness);
        Assert.True(readiness.IsEligible);

        var deactivated = await harness.Catalog.SaveAsync(
            saved.Id,
            CreateClientRequest(saved.Version) with { IsActive = false },
            CancellationToken.None);
        Assert.Equal(2, deactivated.Version);
        Assert.False(deactivated.IsActive);

        readiness = await harness.Catalog.GetReadinessAsync(saved.Id, CancellationToken.None);
        Assert.NotNull(readiness);
        Assert.False(readiness.IsEligible);
        Assert.Contains(ClientBlockCodes.ClientInactive, readiness.BlockCodes);

        var audit = await harness.Catalog.GetAuditAsync(saved.Id, CancellationToken.None);
        Assert.Equal(["deactivated", "created"], audit.Select(item => item.Action));
        Assert.All(audit, item => Assert.DoesNotContain("11222333000181", item.RedactedDataJson));
    }

    [Fact]
    public async Task LocalCatalogEnforcesDomainValidationUniquenessAndConcurrency()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var invalid = CreateClientRequest() with { PrimaryTaxId = "12.345" };
        var validation = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Catalog.SaveAsync(null, invalid, CancellationToken.None));
        Assert.Contains("CNPJ", validation.Message, StringComparison.Ordinal);

        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var duplicate = CreateClientRequest() with { LegalNameOrFullName = "Outra empresa" };
        var uniqueness = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Catalog.SaveAsync(null, duplicate, CancellationToken.None));
        Assert.Contains("já pertence", uniqueness.Message, StringComparison.Ordinal);

        var conflict = await Assert.ThrowsAsync<HttpRequestException>(() =>
            harness.Catalog.SaveAsync(
                saved.Id,
                CreateClientRequest(expectedVersion: 0),
                CancellationToken.None));
        Assert.Equal(System.Net.HttpStatusCode.Conflict, conflict.StatusCode);
    }

    [Fact]
    public async Task LocalCatalogStatusCommandIsPersistentIdempotentAndIndependentFromEditorDraft()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);

        var inactive = await harness.Maintenance.SetClientActiveAsync(
            saved.Id,
            saved.Version,
            false,
            CancellationToken.None);
        Assert.False(inactive.IsActive);
        Assert.Equal(saved.Version + 1, inactive.Version);

        var repeated = await harness.Maintenance.SetClientActiveAsync(
            inactive.Id,
            inactive.Version,
            false,
            CancellationToken.None);
        Assert.Equal(inactive.Id, repeated.Id);
        Assert.Equal(inactive.Version, repeated.Version);
        Assert.False(repeated.IsActive);

        var reactivated = await harness.Maintenance.SetClientActiveAsync(
            repeated.Id,
            repeated.Version,
            true,
            CancellationToken.None);
        Assert.True(reactivated.IsActive);
        Assert.Equal(repeated.Version + 1, reactivated.Version);
        Assert.True((await harness.Catalog.GetAsync(saved.Id, CancellationToken.None))!.IsActive);

        var audit = await harness.Catalog.GetAuditAsync(saved.Id, CancellationToken.None);
        Assert.Equal(
            ["reactivated", "deactivated", "created"],
            audit.Select(item => item.Action));
    }

    [Fact]
    public async Task LocalCatalogStatusAndApprovalRevalidationCommitTogether()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var filePath = Path.Combine(Path.GetTempPath(), $"folhas-status-atomic-{Guid.NewGuid():N}.pdf");
        await File.WriteAllTextAsync(filePath, "PDF sintético para consistência transacional.");
        try
        {
            using var reviewService = harness.CreateReviewService();
            var workspace = await reviewService.ImportAsync(
                filePath,
                CreateReviewRecognition(filePath),
                CancellationToken.None);
            workspace = await reviewService.ApproveGroupAsync(
                Assert.Single(workspace.Groups).Id,
                CancellationToken.None);
            Assert.True(Assert.Single(workspace.Groups).IsApproved);

            var maintenance = harness.CreateCoordinatedMaintenance(reviewService);
            var inactive = await maintenance.SetClientActiveAsync(
                saved.Id,
                saved.Version,
                false,
                CancellationToken.None);

            Assert.False(inactive.IsActive);
            Assert.False((await harness.Catalog.GetAsync(saved.Id, CancellationToken.None))!.IsActive);
            var persistedReview = await harness.LoadReviewWorkspaceAsync();
            Assert.DoesNotContain(persistedReview.Groups, group => group.IsApproved);
            Assert.Contains(persistedReview.Documents, document =>
                document.Findings.Any(finding => finding.RuleCode == "client.inactive"));
            Assert.Contains(persistedReview.AuditEvents, audit =>
                audit.Action == "group.approval_invalidated");
        }
        finally
        {
            File.Delete(filePath);
        }
    }

    [Fact]
    public async Task LocalCatalogRollsBackStatusWhenRevalidationFailsAfterSavingApprovalChanges()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var filePath = Path.Combine(Path.GetTempPath(), $"folhas-status-rollback-{Guid.NewGuid():N}.pdf");
        await File.WriteAllTextAsync(filePath, "PDF sintético para rollback transacional.");
        try
        {
            using var reviewService = harness.CreateReviewService();
            var workspace = await reviewService.ImportAsync(
                filePath,
                CreateReviewRecognition(filePath),
                CancellationToken.None);
            workspace = await reviewService.ApproveGroupAsync(
                Assert.Single(workspace.Groups).Id,
                CancellationToken.None);
            Assert.True(Assert.Single(workspace.Groups).IsApproved);

            var maintenance = harness.CreateCoordinatedMaintenance(
                new FailAfterRevalidationService(reviewService));
            var failure = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                maintenance.SetClientActiveAsync(
                    saved.Id,
                    saved.Version,
                    false,
                    CancellationToken.None));

            Assert.Contains("após persistir", failure.Message, StringComparison.OrdinalIgnoreCase);
            var persistedClient = await harness.Catalog.GetAsync(saved.Id, CancellationToken.None);
            Assert.NotNull(persistedClient);
            Assert.True(persistedClient.IsActive);
            Assert.Equal(saved.Version, persistedClient.Version);
            var persistedReview = await harness.LoadReviewWorkspaceAsync();
            Assert.True(Assert.Single(persistedReview.Groups).IsApproved);
            Assert.DoesNotContain(persistedReview.AuditEvents, audit =>
                audit.Action == "group.approval_invalidated");
            Assert.Equal(
                ["created"],
                (await harness.Catalog.GetAuditAsync(saved.Id, CancellationToken.None))
                    .Select(audit => audit.Action));
        }
        finally
        {
            File.Delete(filePath);
        }
    }

    [Fact]
    public async Task LocalCatalogRollsBackClientEditWhenApprovalRevalidationFails()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var filePath = Path.Combine(Path.GetTempPath(), $"folhas-edit-rollback-{Guid.NewGuid():N}.pdf");
        await File.WriteAllTextAsync(filePath, "PDF sintético para rollback de edição cadastral.");
        try
        {
            using var reviewService = harness.CreateReviewService();
            var workspace = await reviewService.ImportAsync(
                filePath,
                CreateReviewRecognition(filePath),
                CancellationToken.None);
            workspace = await reviewService.ApproveGroupAsync(
                Assert.Single(workspace.Groups).Id,
                CancellationToken.None);
            Assert.True(Assert.Single(workspace.Groups).IsApproved);

            var coordinatedCatalog = harness.CreateCoordinatedMaintenance(
                new FailAfterRevalidationService(reviewService));
            var failure = await Assert.ThrowsAsync<InvalidOperationException>(() =>
                coordinatedCatalog.SaveAsync(
                    saved.Id,
                    CreateClientRequest(saved.Version) with
                    {
                        PreferredName = "Nome que não pode ficar parcialmente salvo",
                    },
                    CancellationToken.None));

            Assert.Contains("após persistir", failure.Message, StringComparison.OrdinalIgnoreCase);
            var persistedClient = await harness.Catalog.GetAsync(saved.Id, CancellationToken.None);
            Assert.NotNull(persistedClient);
            Assert.Equal(saved.PreferredName, persistedClient.PreferredName);
            Assert.Equal(saved.Version, persistedClient.Version);
            Assert.True(Assert.Single((await harness.LoadReviewWorkspaceAsync()).Groups).IsApproved);
            Assert.Equal(
                ["created"],
                (await harness.Catalog.GetAuditAsync(saved.Id, CancellationToken.None))
                    .Select(audit => audit.Action));
        }
        finally
        {
            File.Delete(filePath);
        }
    }

    [Fact]
    public async Task LocalCatalogRejectsEmailWithoutCompleteDomain()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var request = CreateClientRequest() with
        {
            Recipients =
            [
                new RecipientModel(
                    Guid.Empty,
                    null,
                    "Financeiro",
                    "financeiro@empresa",
                    DeliveryRoleModel.To,
                    null,
                    true,
                    true,
                    null,
                    null),
            ],
        };

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Catalog.SaveAsync(null, request, CancellationToken.None));
        Assert.Contains("domínio completos", exception.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task LegacyInvalidRecipientEmailOpensWithExplicitBlockAndCanBeCorrectedOnSave()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var clientId = Guid.NewGuid();
        var invalidRecipientId = Guid.NewGuid();
        var legacy = new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa Legada de E-mail Sintetica Ltda.",
            "Empresa Legada de E-mail",
            "LEG-EMAIL-001",
            "11222333000181",
            false,
            null,
            null,
            null,
            4,
            Timestamp,
            Timestamp,
            [],
            [],
            [
                new RecipientModel(
                    invalidRecipientId,
                    null,
                    "Financeiro legado",
                    "financeiro@empresa",
                    DeliveryRoleModel.To,
                    null,
                    true,
                    true,
                    null,
                    null),
            ],
            []);
        await harness.SeedLocalCatalogClientAsync(legacy);

        var reactivated = await harness.Maintenance.SetClientActiveAsync(
            clientId,
            legacy.Version,
            true,
            CancellationToken.None);
        Assert.True(reactivated.IsActive);
        Assert.Equal("financeiro@empresa", Assert.Single(reactivated.Recipients).Email);
        var payloadBeforeReadiness = await harness.GetLocalClientPayloadAsync(clientId);

        var search = await harness.Catalog.SearchAsync(
            "Empresa Legada de E-mail",
            null,
            null,
            CancellationToken.None);
        Assert.Equal(clientId, Assert.Single(search.Items).Id);
        var loaded = await harness.Catalog.GetAsync(clientId, CancellationToken.None);
        Assert.NotNull(loaded);
        Assert.Equal("financeiro@empresa", Assert.Single(loaded.Recipients).Email);

        var readiness = await harness.Catalog.GetReadinessAsync(clientId, CancellationToken.None);
        Assert.NotNull(readiness);
        Assert.False(readiness.IsEligible);
        Assert.Contains(ClientBlockCodes.RecipientEmailInvalid, readiness.BlockCodes);
        Assert.Contains(ClientBlockCodes.NoActiveToRecipient, readiness.BlockCodes);
        Assert.Equal(payloadBeforeReadiness, await harness.GetLocalClientPayloadAsync(clientId));

        var unchanged = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Catalog.SaveAsync(
                clientId,
                ToMutation(loaded),
                CancellationToken.None));
        Assert.Contains("e-mail", unchanged.Message, StringComparison.OrdinalIgnoreCase);

        var corrected = await harness.Catalog.SaveAsync(
            clientId,
            ToMutation(loaded) with
            {
                Recipients =
                [
                    new RecipientModel(
                        Guid.Empty,
                        null,
                        "Financeiro corrigido",
                        "financeiro@empresa.com.br",
                        DeliveryRoleModel.To,
                        null,
                        true,
                        true,
                        null,
                        null),
                ],
            },
            CancellationToken.None);
        Assert.Equal(reactivated.Version + 1, corrected.Version);
        Assert.Equal("financeiro@empresa.com.br", Assert.Single(corrected.Recipients).Email);
        var correctedReadiness = await harness.Catalog.GetReadinessAsync(clientId, CancellationToken.None);
        Assert.NotNull(correctedReadiness);
        Assert.True(correctedReadiness.IsEligible);
    }

    [Fact]
    public async Task InactiveLegacyEmailsDoNotBlockReadinessOrAnUnrelatedUpdate()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var clientId = Guid.NewGuid();
        var validRecipientId = Guid.NewGuid();
        var invalidInactiveRecipientId = Guid.NewGuid();
        var invalidInactivePartnerId = Guid.NewGuid();
        var legacy = new ClientDetails(
            clientId,
            PersonTypeModel.LegalEntity,
            "Empresa com Contato Histórico Sintética Ltda.",
            "Empresa com Contato Histórico",
            "LEG-EMAIL-002",
            "11222333000181",
            true,
            null,
            null,
            null,
            2,
            Timestamp,
            Timestamp,
            [],
            [],
            [
                new RecipientModel(
                    validRecipientId,
                    null,
                    "Financeiro atual",
                    "financeiro@example.com",
                    DeliveryRoleModel.To,
                    null,
                    true,
                    true,
                    null,
                    null),
                new RecipientModel(
                    invalidInactiveRecipientId,
                    null,
                    "Contato histórico",
                    "antigo@empresa",
                    DeliveryRoleModel.Cc,
                    null,
                    false,
                    false,
                    null,
                    null),
            ],
            [
                new ClientPartnerModel(
                    invalidInactivePartnerId,
                    "Sócio histórico",
                    null,
                    ClientPartnerRoleModel.Partner,
                    false,
                    "socio@empresa"),
            ]);
        await harness.SeedLocalCatalogClientAsync(legacy);

        var readiness = await harness.Catalog.GetReadinessAsync(clientId, CancellationToken.None);
        Assert.NotNull(readiness);
        Assert.True(readiness.IsEligible);
        Assert.DoesNotContain(ClientBlockCodes.RecipientEmailInvalid, readiness.BlockCodes);
        Assert.DoesNotContain(ClientBlockCodes.PartnerEmailInvalid, readiness.BlockCodes);

        var updated = await harness.Catalog.SaveAsync(
            clientId,
            ToMutation(legacy) with { PreferredName = "Empresa Atualizada" },
            CancellationToken.None);

        Assert.Equal("Empresa Atualizada", updated.PreferredName);
        Assert.Equal("financeiro@example.com", Assert.Single(updated.Recipients).Email);
        Assert.Empty(updated.Partners ?? []);
    }

    [Fact]
    public async Task LocalCatalogUpdatesExistingClientWithoutDeletingItAndKeepsRemovedChildrenInactive()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(null, CreateClientRequest(), CancellationToken.None);
        var originalRecipient = Assert.Single(saved.Recipients);

        var updated = await harness.Catalog.SaveAsync(
            saved.Id,
            ToMutation(saved) with
            {
                LegalNameOrFullName = "Empresa Sintética Atualizada Ltda.",
                Recipients = [],
            },
            CancellationToken.None);

        Assert.Equal(saved.Id, updated.Id);
        Assert.True(updated.IsActive);
        Assert.Equal("Empresa Sintética Atualizada Ltda.", updated.LegalNameOrFullName);
        var retainedRecipient = Assert.Single(updated.Recipients);
        Assert.Equal(originalRecipient.Id, retainedRecipient.Id);
        Assert.False(retainedRecipient.IsActive);
        Assert.NotNull(await harness.Catalog.GetAsync(saved.Id, CancellationToken.None));
    }

    [Fact]
    public async Task IndividualCpfCanMatchPartnerCpfWithoutResolvingToTheCompany()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var company = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest() with
            {
                Partners =
                [
                    new ClientPartnerModel(
                        Guid.Empty,
                        "Sócia Administradora",
                        "529.982.247-25",
                        ClientPartnerRoleModel.ManagingPartner,
                        true,
                        "socia@example.com"),
                ],
            },
            CancellationToken.None);
        var individual = await harness.Catalog.SaveAsync(
            null,
            CreateIndividualRequest(),
            CancellationToken.None);

        Assert.NotEqual(company.Id, individual.Id);
        var evidence = new EvidenceBox(1, 1, 1, 10, 10, "529.982.247-25");
        var result = await harness.Resolver.ResolveAsync(
            new ClientResolutionRequest(
            [
                new RecognizedField(
                    "cpf-cliente",
                    "529.982.247-25",
                    "529.982.247-25",
                    SemanticFieldRole.ClientTaxId,
                    .99m,
                    evidence),
            ]),
            CancellationToken.None);
        Assert.True(result.IsResolved);
        Assert.Equal(individual.Id, result.ClientId);

        var partnerOnly = await harness.Resolver.ResolveAsync(
            new ClientResolutionRequest(
            [
                new RecognizedField(
                    "cpf-socio",
                    "529.982.247-25",
                    "529.982.247-25",
                    SemanticFieldRole.PartnerCpf,
                    .99m,
                    evidence),
            ]),
            CancellationToken.None);
        Assert.False(partnerOnly.IsResolved);
        Assert.DoesNotContain(partnerOnly.Alternatives, item => item.ClientId == company.Id);
    }

    [Fact]
    public async Task LegacyPartnerCpfStoredAsCompanyIdentifierIsQuarantinedAndDoesNotBlockIndividual()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var companyId = Guid.NewGuid();
        var misplacedIdentifierId = Guid.NewGuid();
        await harness.SeedLocalCatalogClientAsync(new ClientDetails(
            companyId,
            PersonTypeModel.LegalEntity,
            "Empresa Legada Sintetica Ltda.",
            "Empresa Legada",
            "LEG-001",
            "11222333000181",
            false,
            null,
            null,
            null,
            7,
            Timestamp,
            Timestamp,
            [
                new ClientIdentifierModel(
                    misplacedIdentifierId,
                    ClientIdentifierTypeModel.Cpf,
                    "52998224725",
                    ClientIdentifierSemanticRoleModel.Other,
                    100,
                    true,
                    true),
            ],
            [],
            [],
            [
                new ClientPartnerModel(
                    Guid.NewGuid(),
                    "Socia Administradora Legada",
                    "52998224725",
                    ClientPartnerRoleModel.ManagingPartner,
                    true,
                    "socia.legada@example.com"),
            ]));

        var reactivated = await harness.Maintenance.SetClientActiveAsync(
            companyId,
            7,
            true,
            CancellationToken.None);
        var quarantined = Assert.Single(reactivated.Identifiers);
        Assert.Equal(misplacedIdentifierId, quarantined.Id);
        Assert.False(quarantined.IsActive);
        Assert.True(reactivated.IsActive);

        var individual = await harness.Catalog.SaveAsync(
            null,
            CreateIndividualRequest(),
            CancellationToken.None);
        Assert.NotEqual(companyId, individual.Id);

        var evidence = new EvidenceBox(1, 1, 1, 10, 10, "529.982.247-25");
        var resolution = await harness.Resolver.ResolveAsync(
            new ClientResolutionRequest(
            [
                new RecognizedField(
                    "cpf-cliente",
                    "529.982.247-25",
                    "529.982.247-25",
                    SemanticFieldRole.ClientTaxId,
                    .99m,
                    evidence),
            ]),
            CancellationToken.None);
        Assert.True(resolution.IsResolved);
        Assert.Equal(individual.Id, resolution.ClientId);
    }

    [Fact]
    public async Task LocalCatalogRejectsAnotherCompanyFromTheSameCnpjRoot()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        _ = await harness.Catalog.SaveAsync(null, CreateClientRequest(), CancellationToken.None);
        var duplicateRoot = CreateClientRequest() with
        {
            LegalNameOrFullName = "Outra filial sintética",
            PreferredName = "Outra filial",
            InternalCode = "EMP-002",
            PrimaryTaxId = "11.222.333/0002-62",
        };

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Catalog.SaveAsync(null, duplicateRoot, CancellationToken.None));
        Assert.Contains("raiz", exception.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task LocalArchiveRequiresInactiveAndPreservesPayloadAndAudit()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var saved = await harness.Catalog.SaveAsync(null, CreateClientRequest(), CancellationToken.None);
        await Assert.ThrowsAsync<InvalidOperationException>(() => harness.Maintenance.ArchiveClientAsync(
            saved.Id,
            saved.Version,
            CancellationToken.None));

        var inactive = await harness.Maintenance.SetClientActiveAsync(
            saved.Id,
            saved.Version,
            false,
            CancellationToken.None);
        var archived = await harness.Maintenance.ArchiveClientAsync(
            inactive.Id,
            inactive.Version,
            CancellationToken.None);

        Assert.Equal(inactive.Version + 1, archived.ArchivedVersion);
        Assert.Null(await harness.Catalog.GetAsync(saved.Id, CancellationToken.None));
        Assert.Contains("local-client-archived", await harness.GetRecordTypesAsync());
        Assert.Equal(
            "archived",
            (await harness.Catalog.GetAuditAsync(saved.Id, CancellationToken.None))[0].Action);
    }

    [Fact]
    public async Task TemplateCannotBeInactivatedOrArchivedWhileItIsAClientDefault()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var client = await harness.Catalog.SaveAsync(null, CreateClientRequest(), CancellationToken.None);
        var template = await harness.Catalog.SaveTemplateAsync(
            null,
            new MessageTemplateMutationRequest(
                0,
                client.Id,
                null,
                "Entrega mensal",
                "Documentos de {{periodo.rotulo}}",
                "Olá, seguem os documentos.",
                SignatureModeModel.Organization,
                true,
                true),
            CancellationToken.None);
        client = await harness.Catalog.SaveAsync(
            client.Id,
            ToMutation(client) with { DefaultBodyTemplateId = template.Id },
            CancellationToken.None);

        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Maintenance.SetTemplateActiveAsync(
                template.Id,
                template.Version,
                false,
                CancellationToken.None));

        client = await harness.Catalog.SaveAsync(
            client.Id,
            ToMutation(client) with { DefaultBodyTemplateId = null },
            CancellationToken.None);
        var inactive = await harness.Maintenance.SetTemplateActiveAsync(
            template.Id,
            template.Version,
            false,
            CancellationToken.None);
        var archived = await harness.Maintenance.ArchiveTemplateAsync(
            inactive.Id,
            inactive.Version,
            CancellationToken.None);
        Assert.Equal(inactive.Version + 1, archived.ArchivedVersion);
        Assert.Empty(await harness.Catalog.GetTemplatesAsync(client.Id, true, CancellationToken.None));
        Assert.Contains("local-message-template-archived", await harness.GetRecordTypesAsync());
    }

    [Fact]
    public async Task ClientArchiveIsRefusedWhileClientSpecificTemplateExists()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var client = await harness.Catalog.SaveAsync(null, CreateClientRequest(), CancellationToken.None);
        _ = await harness.Catalog.SaveTemplateAsync(
            null,
            new MessageTemplateMutationRequest(
                0,
                client.Id,
                null,
                "Mensagem preservada",
                "Documentos de {{periodo.rotulo}}",
                "Olá, seguem os documentos.",
                SignatureModeModel.Organization,
                false,
                true),
            CancellationToken.None);
        client = await harness.Maintenance.SetClientActiveAsync(
            client.Id,
            client.Version,
            false,
            CancellationToken.None);

        var exception = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            harness.Maintenance.ArchiveClientAsync(
                client.Id,
                client.Version,
                CancellationToken.None));
        Assert.Contains("mensagens personalizadas", exception.Message, StringComparison.OrdinalIgnoreCase);
        Assert.NotNull(await harness.Catalog.GetAsync(client.Id, CancellationToken.None));
    }

    [Fact]
    public async Task LocalCatalogPersistsTemplatesAndSupportsDryRunAndRestore()
    {
        await using var source = await LocalCatalogHarness.CreateAsync();
        var client = await source.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var template = await source.Catalog.SaveTemplateAsync(
            null,
            new MessageTemplateMutationRequest(
                0,
                client.Id,
                null,
                "Entrega mensal",
                "Documentos de {{competencia}}",
                "Olá, seguem os documentos.",
                SignatureModeModel.Organization,
                true,
                true),
            CancellationToken.None);
        Assert.Equal(template, Assert.Single(await source.Catalog.GetTemplatesAsync(
            client.Id,
            false,
            CancellationToken.None)));

        var backup = await source.Catalog.ExportAsync(CancellationToken.None);
        Assert.Equal(1, backup.FormatVersion);
        Assert.Single(backup.Clients);
        Assert.Single(backup.Templates);

        await using var target = await LocalCatalogHarness.CreateAsync();
        var dryRun = await target.Catalog.ImportAsync(
            new ClientCatalogImportRequest(true, true, backup),
            CancellationToken.None);
        Assert.True(dryRun.DryRun);
        Assert.Equal(1, dryRun.ClientsCreated);
        Assert.Equal(1, dryRun.TemplatesCreated);
        Assert.Empty((await target.Catalog.SearchAsync(null, null, null, CancellationToken.None)).Items);

        var restored = await target.Catalog.ImportAsync(
            new ClientCatalogImportRequest(false, true, backup),
            CancellationToken.None);
        Assert.False(restored.DryRun);
        var restoredClient = Assert.Single((await target.Catalog.SearchAsync(
            null,
            null,
            null,
            CancellationToken.None)).Items);
        Assert.Equal(client.Id, restoredClient.Id);
        Assert.Equal(template.Id, Assert.Single(await target.Catalog.GetTemplatesAsync(
            client.Id,
            false,
            CancellationToken.None)).Id);

        var skipped = await target.Catalog.ImportAsync(
            new ClientCatalogImportRequest(false, false, backup),
            CancellationToken.None);
        Assert.Equal(0, skipped.ClientsUpdated);
        Assert.Equal(0, skipped.TemplatesUpdated);
        Assert.Equal(2, skipped.Warnings.Count);
    }

    [Fact]
    public async Task LocalResolverUsesThePersistedCatalogWithoutAConnectedServer()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var client = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var evidence = new EvidenceBox(1, 10, 20, 30, 12, "11.222.333/0001-81");
        var result = await harness.Resolver.ResolveAsync(
            new ClientResolutionRequest(
            [
                new RecognizedField(
                    "cnpj",
                    "11.222.333/0001-81",
                    "11.222.333/0001-81",
                    SemanticFieldRole.ClientTaxId,
                    .99m,
                    evidence),
            ]),
            CancellationToken.None);

        Assert.True(result.IsResolved);
        Assert.Equal(client.Id, result.ClientId);
        Assert.Equal(ClientResolutionMethod.ExactClientTaxId, result.Method);
        Assert.Equal(evidence, Assert.Single(result.Evidence));
        Assert.Empty(result.Blockers);

        var rootResult = await harness.Resolver.ResolveAsync(
            new ClientResolutionRequest(
            [
                new RecognizedField(
                    "raiz-cnpj",
                    "11.222.333",
                    "11.222.333",
                    SemanticFieldRole.ClientTaxId,
                    .90m,
                    evidence),
                new RecognizedField(
                    "empresa",
                    "EMPRESA SINTETICA LTDA",
                    "Empresa Sintética Ltda.",
                    SemanticFieldRole.ClientName,
                    .90m,
                    evidence),
            ]),
            CancellationToken.None);

        Assert.True(rootResult.IsResolved);
        Assert.Equal(client.Id, rootResult.ClientId);
        Assert.Equal(ClientResolutionMethod.UniqueCnpjRootAndName, rootResult.Method);
    }

    [Fact]
    public async Task LocalCatalogNeverReadsOrOverwritesTheConnectedCatalogCache()
    {
        await using var harness = await LocalCatalogHarness.CreateAsync();
        var local = await harness.Catalog.SaveAsync(
            null,
            CreateClientRequest(),
            CancellationToken.None);
        var connectedOnly = local with
        {
            Id = Guid.NewGuid(),
            LegalNameOrFullName = "Cliente somente do cache conectado",
            PrimaryTaxId = "44555666000177",
        };
        await harness.SeedConnectedCacheClientAsync(connectedOnly);

        var localSearch = await harness.Catalog.SearchAsync(
            null,
            null,
            null,
            CancellationToken.None);
        Assert.Equal(local.Id, Assert.Single(localSearch.Items).Id);
        Assert.Null(await harness.Catalog.GetAsync(connectedOnly.Id, CancellationToken.None));
        Assert.Contains("local-client", await harness.GetRecordTypesAsync());
        Assert.Contains("client", await harness.GetRecordTypesAsync());
    }

    [Fact]
    public async Task LocalDesktopOperationContextAllowsOnlyTheSafeLocalWorkflow()
    {
        var accessor = new LocalDesktopOperationContextAccessor();

        var reviewContext = await accessor.GetCurrentAsync(CancellationToken.None);
        var dispatchContext = await ((IDispatchExecutionContextAccessor)accessor)
            .GetCurrentAsync(CancellationToken.None);

        Assert.Equal(LocalDesktopOperationContextAccessor.LocalScopeKey, reviewContext.ScopeKey);
        Assert.Equal(LocalDesktopOperationContextAccessor.LocalActorId, reviewContext.ActorId);
        Assert.Equal(reviewContext.ScopeKey, dispatchContext.ScopeKey);
        Assert.Equal(reviewContext.ActorId, dispatchContext.ActorId);
        Assert.Equal(
            [
                AppPermissions.AuditExport,
                AppPermissions.BatchApprove,
                AppPermissions.DocumentsProcess,
                AppPermissions.EmailDraft,
            ],
            dispatchContext.Permissions.Order(StringComparer.Ordinal));
        Assert.DoesNotContain(AppPermissions.EmailSend, dispatchContext.Permissions);
    }

    [Fact]
    public void DesktopRegistrationDefaultsToLocalCatalogAndDisabledPilot()
    {
        var services = new ServiceCollection();
        services.AddSingleton<ISecretStore, InMemorySecretStore>();
        services.AddDesktopPhase2(new ConfigurationBuilder().Build());
        using var provider = services.BuildServiceProvider();

        Assert.Contains(services, descriptor =>
            descriptor.ServiceType == typeof(IClientCatalogService) &&
            descriptor.ImplementationType == typeof(SqliteLocalClientCatalogService));
        Assert.Contains(services, descriptor =>
            descriptor.ServiceType == typeof(ILocalClientCatalogMaintenance));
        Assert.Contains(services, descriptor =>
            descriptor.ServiceType == typeof(IClientResolver) &&
            descriptor.ImplementationType == typeof(SqliteLocalClientResolver));
        var reviewContext = provider.GetRequiredService<IDocumentReviewContextAccessor>();
        var dispatchContext = provider.GetRequiredService<IDispatchExecutionContextAccessor>();
        Assert.IsType<LocalDesktopOperationContextAccessor>(reviewContext);
        Assert.Same(reviewContext, dispatchContext);
        Assert.False(provider.GetRequiredService<PilotModeOptions>().Enabled);
    }

    [Fact]
    public async Task DesktopRegistrationPreservesHttpCatalogForExplicitConnectedApi()
    {
        var settings = new Dictionary<string, string?>
        {
            ["Phase2:ApiBaseAddress"] = "https://api.example.invalid/",
            ["Phase11:Enabled"] = "true",
        };
        var services = new ServiceCollection();
        services.AddSingleton<ISecretStore, InMemorySecretStore>();
        services.AddDesktopPhase2(new ConfigurationBuilder().AddInMemoryCollection(settings).Build());
        using var provider = services.BuildServiceProvider();

        Assert.DoesNotContain(services, descriptor =>
            descriptor.ServiceType == typeof(IClientCatalogService) &&
            descriptor.ImplementationType == typeof(SqliteLocalClientCatalogService));
        Assert.DoesNotContain(services, descriptor =>
            descriptor.ServiceType == typeof(ILocalClientCatalogMaintenance));
        using var scope = provider.CreateScope();
        Assert.IsType<HttpClientCatalogService>(scope.ServiceProvider.GetRequiredService<IClientCatalogService>());
        Assert.IsType<HttpClientResolver>(scope.ServiceProvider.GetRequiredService<IClientResolver>());
        var reviewContext = provider.GetRequiredService<IDocumentReviewContextAccessor>();
        var dispatchContext = provider.GetRequiredService<IDispatchExecutionContextAccessor>();
        Assert.IsType<JwtDocumentReviewContextAccessor>(reviewContext);
        Assert.Same(reviewContext, dispatchContext);
        var connectedExecutionContext = await dispatchContext.GetCurrentAsync(CancellationToken.None);
        Assert.NotEqual(LocalDesktopOperationContextAccessor.LocalScopeKey, connectedExecutionContext.ScopeKey);
        Assert.Empty(connectedExecutionContext.Permissions);
        Assert.True(provider.GetRequiredService<PilotModeOptions>().Enabled);
    }

    private static ClientMutationRequest CreateClientRequest(long expectedVersion = 0) => new(
        expectedVersion,
        PersonTypeModel.LegalEntity,
        "Empresa Sintética Ltda.",
        "Empresa Sintética",
        "EMP-001",
        "11.222.333/0001-81",
        true,
        null,
        null,
        null,
        [],
        [],
        [
            new RecipientModel(
                Guid.Empty,
                null,
                "Financeiro",
                "financeiro@example.com",
                DeliveryRoleModel.To,
                null,
                true,
                true,
                null,
                null),
        ],
        [
            new ClientPartnerModel(
                Guid.Empty,
                "Sócia Administradora",
                null,
                ClientPartnerRoleModel.ManagingPartner,
                true,
                "socia@example.com"),
        ]);

    private static ClientMutationRequest CreateIndividualRequest() => new(
        0,
        PersonTypeModel.Individual,
        "Pessoa Física Sintética",
        "Pessoa Sintética",
        "PF-001",
        "529.982.247-25",
        true,
        null,
        null,
        null,
        [],
        [],
        [
            new RecipientModel(
                Guid.Empty,
                null,
                "Pessoa Física Sintética",
                "pessoa@example.com",
                DeliveryRoleModel.To,
                null,
                true,
                true,
                null,
                null),
        ],
        []);

    private static ClientMutationRequest ToMutation(ClientDetails client) => new(
        client.Version,
        client.PersonType,
        client.LegalNameOrFullName,
        client.PreferredName,
        client.InternalCode,
        client.PrimaryTaxId,
        client.IsActive,
        client.DefaultSubjectTemplateId,
        client.DefaultBodyTemplateId,
        client.Notes,
        client.Identifiers,
        client.Establishments,
        client.Recipients,
        client.Partners);

    private static DocumentRecognitionResult CreateReviewRecognition(string path)
    {
        var evidence = new EvidenceBox(1, 0, 0, 10, 10, "Dado sintético");
        return new DocumentRecognitionResult(
            Path.GetFileName(path),
            Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(path))),
            "application/pdf",
            new FileInfo(path).Length,
            1,
            RecognizedDocumentType.Payroll,
            "synthetic-v1",
            RecognitionConfidence.High,
            .99m,
            false,
            false,
            [
                new RecognizedField(
                    "EmpregadorCnpj",
                    "11222333000181",
                    "11.222.333/0001-81",
                    SemanticFieldRole.EmployerTaxId,
                    .99m,
                    evidence),
                new RecognizedField(
                    "Competencia",
                    "08/2026",
                    "08/2026",
                    SemanticFieldRole.Competence,
                    .99m,
                    evidence),
                new RecognizedField(
                    "ValorTotal",
                    "1234.56",
                    "1.234,56",
                    SemanticFieldRole.TotalAmount,
                    .99m,
                    evidence),
            ],
            new ClientResolutionResult(
                null,
                null,
                null,
                null,
                ClientResolutionMethod.None,
                0m,
                [],
                [],
                ["client.not_resolved"]),
            []);
    }

    private sealed class FailAfterRevalidationService(IDocumentReviewService inner)
        : IDocumentReviewService
    {
        public Task<DocumentReviewWorkspace> LoadAsync(CancellationToken cancellationToken) =>
            inner.LoadAsync(cancellationToken);

        public Task<DocumentReviewWorkspace> ImportAsync(
            string localPath,
            DocumentRecognitionResult recognition,
            CancellationToken cancellationToken) => inner.ImportAsync(localPath, recognition, cancellationToken);

        public async Task<DocumentReviewWorkspace> RevalidateAsync(CancellationToken cancellationToken)
        {
            _ = await inner.RevalidateAsync(cancellationToken);
            throw new InvalidOperationException("Falha sintética após persistir a revalidação.");
        }

        public Task<DocumentReviewWorkspace> CorrectPeriodAsync(
            Guid documentId,
            DocumentPeriod period,
            string reason,
            CancellationToken cancellationToken) =>
            inner.CorrectPeriodAsync(documentId, period, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> RestoreExtractedPeriodAsync(
            Guid documentId,
            string reason,
            CancellationToken cancellationToken) =>
            inner.RestoreExtractedPeriodAsync(documentId, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> RemoveDocumentAsync(
            Guid documentId,
            string reason,
            CancellationToken cancellationToken) =>
            inner.RemoveDocumentAsync(documentId, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> OverrideClientAsync(
            Guid documentId,
            ClientResolutionCandidate candidate,
            string reason,
            CancellationToken cancellationToken) =>
            inner.OverrideClientAsync(documentId, candidate, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> SplitGroupAsync(
            Guid groupId,
            IReadOnlyCollection<Guid> documentIds,
            string reason,
            CancellationToken cancellationToken) =>
            inner.SplitGroupAsync(groupId, documentIds, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> MergeGroupsAsync(
            Guid targetGroupId,
            Guid sourceGroupId,
            string reason,
            CancellationToken cancellationToken) =>
            inner.MergeGroupsAsync(targetGroupId, sourceGroupId, reason, cancellationToken);

        public Task<DocumentReviewWorkspace> ApproveGroupAsync(
            Guid groupId,
            CancellationToken cancellationToken) => inner.ApproveGroupAsync(groupId, cancellationToken);

        public Task<DocumentReviewWorkspace> ApproveGroupsAsync(
            IReadOnlyCollection<Guid> groupIds,
            CancellationToken cancellationToken) => inner.ApproveGroupsAsync(groupIds, cancellationToken);

        public Task<DocumentReviewWorkspace> ApproveAllEligibleAsync(CancellationToken cancellationToken) =>
            inner.ApproveAllEligibleAsync(cancellationToken);
    }

    private sealed class LocalCatalogHarness : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        private readonly LocalCacheDbContext context;

        private LocalCatalogHarness(SqliteConnection connection, LocalCacheDbContext context)
        {
            this.connection = connection;
            this.context = context;
            var clock = new FixedClock(Timestamp);
            Catalog = new SqliteLocalClientCatalogService(context, clock);
            Resolver = new SqliteLocalClientResolver(context);
        }

        public SqliteLocalClientCatalogService Catalog { get; }

        public SqliteLocalClientCatalogService Maintenance => Catalog;

        public SqliteLocalClientResolver Resolver { get; }

        public SqliteLocalClientCatalogService CreateCoordinatedMaintenance(
            IDocumentReviewService reviewService) => new(context, new FixedClock(Timestamp), reviewService);

        public DocumentReviewService CreateReviewService()
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
                new SqliteDocumentReviewStore(context),
                new LocalDesktopOperationContextAccessor(),
                new DocumentPeriodParser(),
                rules,
                reviewOptions,
                new FixedClock(Timestamp),
                Resolver);
        }

        public Task<DocumentReviewWorkspace> LoadReviewWorkspaceAsync() =>
            new SqliteDocumentReviewStore(context).LoadAsync(
                LocalDesktopOperationContextAccessor.LocalScopeKey,
                CancellationToken.None);

        public async Task SeedConnectedCacheClientAsync(ClientDetails client)
        {
            context.CatalogRecords.Add(new CatalogCacheRecord
            {
                RecordType = "client",
                RecordId = client.Id,
                JsonPayload = JsonSerializer.Serialize(client, SerializerOptions),
                Version = client.Version,
                UpdatedAtUtc = client.UpdatedAtUtc,
            });
            await context.SaveChangesAsync();
        }

        public async Task SeedLocalCatalogClientAsync(ClientDetails client)
        {
            context.CatalogRecords.Add(new CatalogCacheRecord
            {
                RecordType = "local-client",
                RecordId = client.Id,
                JsonPayload = JsonSerializer.Serialize(client, SerializerOptions),
                Version = client.Version,
                UpdatedAtUtc = client.UpdatedAtUtc,
            });
            await context.SaveChangesAsync();
        }

        public Task<string> GetLocalClientPayloadAsync(Guid clientId) => context.CatalogRecords.AsNoTracking()
            .Where(record => record.RecordType == "local-client" && record.RecordId == clientId)
            .Select(record => record.JsonPayload)
            .SingleAsync();

        public Task<string[]> GetRecordTypesAsync() => context.CatalogRecords.AsNoTracking()
            .Select(record => record.RecordType)
            .Distinct()
            .ToArrayAsync();

        public static async Task<LocalCatalogHarness> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
                .UseSqlite(connection)
                .Options;
            var context = new LocalCacheDbContext(options);
            await context.Database.EnsureCreatedAsync();
            return new LocalCatalogHarness(connection, context);
        }

        public async ValueTask DisposeAsync()
        {
            await context.DisposeAsync();
            await connection.DisposeAsync();
        }
    }

    private sealed class FixedClock(DateTimeOffset utcNow) : IClock
    {
        public DateTimeOffset UtcNow { get; } = utcNow;
    }
}
