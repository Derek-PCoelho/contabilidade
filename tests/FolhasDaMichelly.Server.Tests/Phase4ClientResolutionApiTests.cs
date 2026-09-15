using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Documents;
using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase4ClientResolutionApiTests : IClassFixture<Phase2WebApplicationFactory>
{
    private readonly Phase2WebApplicationFactory factory;

    public Phase4ClientResolutionApiTests(Phase2WebApplicationFactory factory)
    {
        this.factory = factory;
    }

    [Fact]
    public async Task UnionAndWorkerAppearingFirstNeverOverrideEmployerAndTenantIsIsolated()
    {
        var organizationId = Guid.NewGuid();
        using var client = await CreateClientAsync(organizationId);
        var employer = await AddCatalogClientAsync(
            client,
            PersonTypeModel.LegalEntity,
            "MICHELLY SERVICOS CONTABEIS LTDA",
            "EMP-001",
            "11.222.333/0001-81");
        await AddCatalogClientAsync(
            client,
            PersonTypeModel.LegalEntity,
            "SINDICATO SINTETICO",
            "SIND-001",
            "04.252.011/0001-10");
        await AddCatalogClientAsync(
            client,
            PersonTypeModel.Individual,
            "TRABALHADOR SINTETICO",
            "PF-001",
            "529.982.247-25");

        using var otherTenant = await CreateClientAsync(Guid.NewGuid());
        var otherEmployer = await AddCatalogClientAsync(
            otherTenant,
            PersonTypeModel.LegalEntity,
            "OUTRA ORGANIZACAO",
            "EMP-OUTRO",
            "11.222.333/0001-81");

        var fields = new[]
        {
            Field("SindicatoCnpj", "04252011000110", SemanticFieldRole.UnionTaxId, 1),
            Field("TrabalhadorCpf", "52998224725", SemanticFieldRole.EmployeeCpf, 1),
            Field("EmpregadorCnpj", "11222333000181", SemanticFieldRole.EmployerTaxId, 1),
        };
        using var response = await client.PostAsJsonAsync(
            "/api/document-recognition/resolve-client",
            new ClientResolutionRequest(fields));
        var resolution = await response.Content.ReadFromJsonAsync<ClientResolutionResult>();

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(resolution);
        Assert.True(resolution.IsResolved);
        Assert.Equal(employer.Id, resolution.ClientId);
        Assert.NotEqual(otherEmployer.Id, resolution.ClientId);
        Assert.Equal(ClientResolutionMethod.ExactClientTaxId, resolution.Method);
    }

    [Fact]
    public async Task AmbiguousCnpjRootBlocksAutomaticResolution()
    {
        using var client = await CreateClientAsync(Guid.NewGuid());
        await AddCatalogClientAsync(
            client,
            PersonTypeModel.LegalEntity,
            "GRUPO SINTETICO MATRIZ",
            "ROOT-001",
            "11.222.333/0001-81");
        await AddCatalogClientAsync(
            client,
            PersonTypeModel.LegalEntity,
            "GRUPO SINTETICO INDEPENDENTE",
            "ROOT-002",
            "11.222.333/0002-62");

        using var response = await client.PostAsJsonAsync(
            "/api/document-recognition/resolve-client",
            new ClientResolutionRequest(
                [Field("EmpregadorCnpj", "11222333000343", SemanticFieldRole.EmployerTaxId, 1)]));
        var resolution = await response.Content.ReadFromJsonAsync<ClientResolutionResult>();

        Assert.NotNull(resolution);
        Assert.False(resolution.IsResolved);
        Assert.Contains("client.cnpj_root_ambiguous", resolution.Blockers);
        Assert.Equal(2, resolution.Alternatives.Count);
    }

    [Fact]
    public async Task ResolutionEndpointRequiresClientReadPermission()
    {
        using var anonymous = factory.CreateClient();
        using var response = await anonymous.PostAsJsonAsync(
            "/api/document-recognition/resolve-client",
            new ClientResolutionRequest([]));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    private async Task<HttpClient> CreateClientAsync(Guid organizationId) =>
        await factory.CreateAuthenticatedClientAsync(
            organizationId,
            Guid.NewGuid(),
            Guid.NewGuid(),
            string.Join(',', AppPermissions.ClientsRead, AppPermissions.ClientsWrite),
            hasMfa: true);

    private static async Task<ClientDetails> AddCatalogClientAsync(
        HttpClient client,
        PersonTypeModel personType,
        string name,
        string internalCode,
        string taxId)
    {
        using var response = await client.PostAsJsonAsync(
            "/api/clients",
            new ClientMutationRequest(
                0,
                personType,
                name,
                name,
                internalCode,
                taxId,
                true,
                null,
                null,
                "DADOS SINTETICOS - SEM VALIDADE",
                [],
                [],
                []));
        var body = await response.Content.ReadAsStringAsync();
        Assert.True(response.StatusCode == HttpStatusCode.Created, body);
        return await response.Content.ReadFromJsonAsync<ClientDetails>()
            ?? throw new InvalidOperationException("Synthetic client response was empty.");
    }

    private static RecognizedField Field(
        string name,
        string value,
        SemanticFieldRole role,
        int page) => new(
            name,
            value,
            "***",
            role,
            .96m,
            new EvidenceBox(page, 10m, 10m, 20m, 8m, $"{name}: ***"));
}
