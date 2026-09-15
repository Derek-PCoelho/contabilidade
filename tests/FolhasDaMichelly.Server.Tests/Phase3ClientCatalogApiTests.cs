using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase3ClientCatalogApiTests : IClassFixture<Phase2WebApplicationFactory>
{
    private static readonly JsonSerializerOptions SerializerOptions =
        new(JsonSerializerDefaults.Web);
    private readonly Phase2WebApplicationFactory factory;

    public Phase3ClientCatalogApiTests(Phase2WebApplicationFactory factory)
    {
        this.factory = factory;
    }

    [Fact]
    public async Task CatalogCrudIsTenantScopedAndMasksTaxIdInSearch()
    {
        var organizationId = Guid.NewGuid();
        using var client = await CreateCatalogClientAsync(organizationId, hasMfa: true);
        var created = await CreateClientAsync(client, Mutation("PJ-API-001"));

        var search = await client.GetFromJsonAsync<ClientSearchResponse>(
            "/api/clients?search=PJ-API-001");

        Assert.NotNull(search);
        var item = Assert.Single(search.Items);
        Assert.Equal(created.Id, item.Id);
        Assert.Equal("**.***.***/****-81", item.PrimaryTaxIdMasked);
        Assert.DoesNotContain(
            typeof(ClientMutationRequest).GetProperties(),
            property => property.Name.Contains("Organization", StringComparison.Ordinal));

        using var otherTenant = await CreateCatalogClientAsync(Guid.NewGuid(), hasMfa: true);
        using var isolatedResponse = await otherTenant.GetAsync($"/api/clients/{created.Id:D}");
        Assert.Equal(HttpStatusCode.NotFound, isolatedResponse.StatusCode);
    }

    [Fact]
    public async Task StaleUpdateConflictsAndDeactivationCreatesRedactedAuditAndBlock()
    {
        using var client = await CreateCatalogClientAsync(Guid.NewGuid(), hasMfa: true);
        var created = await CreateClientAsync(client, Mutation("PJ-API-002"));
        var deactivation = new ClientMutationRequest(
            created.Version,
            created.PersonType,
            created.LegalNameOrFullName,
            created.PreferredName,
            created.InternalCode,
            created.PrimaryTaxId,
            false,
            created.DefaultSubjectTemplateId,
            created.DefaultBodyTemplateId,
            created.Notes,
            created.Identifiers,
            created.Establishments,
            created.Recipients);

        using var updatedResponse = await client.PutAsJsonAsync(
            $"/api/clients/{created.Id:D}",
            deactivation);
        var updatedBody = await updatedResponse.Content.ReadAsStringAsync();
        Assert.True(
            updatedResponse.StatusCode == HttpStatusCode.OK,
            $"Expected OK but received {updatedResponse.StatusCode}: {updatedBody}");

        using var staleResponse = await client.PutAsJsonAsync(
            $"/api/clients/{created.Id:D}",
            deactivation);
        Assert.Equal(HttpStatusCode.Conflict, staleResponse.StatusCode);

        var readiness = await client.GetFromJsonAsync<ClientReadinessResponse>(
            $"/api/clients/{created.Id:D}/readiness");
        Assert.NotNull(readiness);
        Assert.False(readiness.IsEligible);
        Assert.Contains(ClientBlockCodes.ClientInactive, readiness.BlockCodes);

        var audit = await client.GetFromJsonAsync<IReadOnlyList<AuditEventModel>>(
            $"/api/clients/{created.Id:D}/audit");
        Assert.NotNull(audit);
        Assert.Contains(audit, item => item.Action == "deactivated");
        Assert.All(audit, item =>
        {
            Assert.DoesNotContain("11222333000181", item.RedactedDataJson, StringComparison.Ordinal);
            Assert.DoesNotContain("example.invalid", item.RedactedDataJson, StringComparison.Ordinal);
        });
    }

    [Fact]
    public async Task TemplatesAndVersionedCatalogDryRunDoNotSendOrPersistAnything()
    {
        using var client = await CreateCatalogClientAsync(Guid.NewGuid(), hasMfa: true);
        var created = await CreateClientAsync(client, Mutation("PJ-API-003"));
        var templateRequest = new MessageTemplateMutationRequest(
            0,
            created.Id,
            null,
            "Modelo de teste",
            "Folhas {{competencia}}",
            "Corpo sintético, sem envio.",
            SignatureModeModel.Organization,
            true,
            true);
        using var templateResponse = await client.PostAsJsonAsync(
            "/api/message-templates",
            templateRequest);
        Assert.Equal(HttpStatusCode.Created, templateResponse.StatusCode);

        var exported = await client.GetFromJsonAsync<ClientCatalogTransferDocument>(
            "/api/clients/catalog/export");
        Assert.NotNull(exported);
        Assert.Equal(1, exported.FormatVersion);
        Assert.Contains(exported.Clients, item => item.Id == created.Id);
        Assert.Single(exported.Templates);

        using var importResponse = await client.PostAsJsonAsync(
            "/api/clients/catalog/import",
            new ClientCatalogImportRequest(true, true, exported));
        Assert.Equal(HttpStatusCode.OK, importResponse.StatusCode);
        var result = await importResponse.Content.ReadFromJsonAsync<ClientCatalogImportResult>();
        Assert.NotNull(result);
        Assert.True(result.DryRun);
        Assert.Equal(1, result.ClientsUpdated);
        Assert.Equal(1, result.TemplatesUpdated);
    }

    [Fact]
    public async Task PartnerEmailRoundTripsNormalizedAndLegacyJsonWithoutItRemainsCompatible()
    {
        using var client = await CreateCatalogClientAsync(Guid.NewGuid(), hasMfa: true);
        var request = Mutation("PJ-API-004") with
        {
            Partners =
            [
                new ClientPartnerModel(
                    Guid.Empty,
                    "Sócia-administradora sintética",
                    "529.982.247-25",
                    ClientPartnerRoleModel.ManagingPartner,
                    true,
                    " Socia.Administradora@Example.INVALID "),
            ],
        };

        var created = await CreateClientAsync(client, request);
        var createdPartner = Assert.Single(created.Partners ?? []);
        Assert.Equal("socia.administradora@example.invalid", createdPartner.Email);

        var fetched = await client.GetFromJsonAsync<ClientDetails>($"/api/clients/{created.Id:D}");
        Assert.NotNull(fetched);
        Assert.Equal(
            "socia.administradora@example.invalid",
            Assert.Single(fetched.Partners ?? []).Email);

        var legacyJson = JsonSerializer.Serialize(
            new
            {
                id = Guid.Empty,
                fullName = "Representante legado sintético",
                cpf = (string?)null,
                role = ClientPartnerRoleModel.LegalRepresentative,
                isActive = true,
            },
            SerializerOptions);
        var legacyPartner = JsonSerializer.Deserialize<ClientPartnerModel>(
            legacyJson,
            SerializerOptions);

        Assert.NotNull(legacyPartner);
        Assert.Null(legacyPartner.Email);
    }

    private async Task<HttpClient> CreateCatalogClientAsync(Guid organizationId, bool hasMfa) =>
        await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            string.Join(',',
                AppPermissions.ClientsRead,
                AppPermissions.ClientsWrite,
                AppPermissions.TemplatesRead,
                AppPermissions.TemplatesWrite),
            hasMfa);

    private static async Task<ClientDetails> CreateClientAsync(
        HttpClient client,
        ClientMutationRequest request)
    {
        using var response = await client.PostAsJsonAsync("/api/clients", request);
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        return await response.Content.ReadFromJsonAsync<ClientDetails>()
            ?? throw new InvalidOperationException("Synthetic client response was empty.");
    }

    private static ClientMutationRequest Mutation(string internalCode) => new(
        0,
        PersonTypeModel.LegalEntity,
        $"Cliente sintético {internalCode}",
        "Cliente de teste",
        internalCode,
        "11.222.333/0001-81",
        true,
        null,
        null,
        "Dados sintéticos.",
        [],
        [
            new EstablishmentModel(
                Guid.Empty,
                "11.222.333/0001-81",
                $"Cliente sintético {internalCode}",
                "Matriz sintética",
                null,
                true,
                true),
        ],
        [
            new RecipientModel(
                Guid.Empty,
                null,
                "Financeiro sintético",
                $"{internalCode.ToLowerInvariant()}@example.invalid",
                DeliveryRoleModel.To,
                null,
                true,
                true,
                null,
                null),
        ]);
}
