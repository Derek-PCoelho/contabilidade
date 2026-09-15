using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Contracts.Clients;

namespace FolhasDaMichelly.Infrastructure.Clients;

public sealed class HttpClientCatalogService(
    HttpClient httpClient,
    IClientCatalogCache cache) : IClientCatalogService
{
    public async Task<ClientSearchResponse> SearchAsync(
        string? search,
        bool? isActive,
        PersonTypeModel? personType,
        CancellationToken cancellationToken)
    {
        var query = new List<string> { "take=200" };
        if (!string.IsNullOrWhiteSpace(search))
        {
            query.Add($"search={Uri.EscapeDataString(search.Trim())}");
        }

        if (isActive is not null)
        {
            query.Add($"isActive={isActive.Value.ToString(CultureInfo.InvariantCulture).ToLowerInvariant()}");
        }

        if (personType is not null)
        {
            query.Add($"personType={personType.Value}");
        }

        using var response = await httpClient.GetAsync(
            $"api/clients?{string.Join('&', query)}",
            cancellationToken);
        response.EnsureSuccessStatusCode();
        return await ReadRequiredAsync<ClientSearchResponse>(response, cancellationToken);
    }

    public async Task<ClientDetails?> GetAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        try
        {
            using var response = await httpClient.GetAsync(
                $"api/clients/{clientId:D}",
                cancellationToken);
            if (response.StatusCode == HttpStatusCode.NotFound)
            {
                return null;
            }

            response.EnsureSuccessStatusCode();
            var client = await ReadRequiredAsync<ClientDetails>(response, cancellationToken);
            await cache.StoreClientAsync(client, cancellationToken);
            return client;
        }
        catch (HttpRequestException)
        {
            return await cache.GetClientAsync(clientId, cancellationToken);
        }
    }

    public async Task<ClientDetails> SaveAsync(
        Guid? clientId,
        ClientMutationRequest request,
        CancellationToken cancellationToken)
    {
        using var response = clientId is null
            ? await httpClient.PostAsJsonAsync("api/clients", request, cancellationToken)
            : await httpClient.PutAsJsonAsync($"api/clients/{clientId:D}", request, cancellationToken);
        response.EnsureSuccessStatusCode();
        var result = await ReadRequiredAsync<ClientDetails>(response, cancellationToken);
        await cache.StoreClientAsync(result, cancellationToken);
        return result;
    }

    public async Task<ClientReadinessResponse?> GetReadinessAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync(
            $"api/clients/{clientId:D}/readiness",
            cancellationToken);
        if (response.StatusCode == HttpStatusCode.NotFound)
        {
            return null;
        }

        response.EnsureSuccessStatusCode();
        return await ReadRequiredAsync<ClientReadinessResponse>(response, cancellationToken);
    }

    public async Task<IReadOnlyList<MessageTemplateModel>> GetTemplatesAsync(
        Guid? clientId,
        bool includeInactive,
        CancellationToken cancellationToken)
    {
        var url = $"api/message-templates?includeInactive={includeInactive.ToString(CultureInfo.InvariantCulture).ToLowerInvariant()}";
        if (clientId is not null)
        {
            url += $"&clientId={clientId:D}";
        }

        try
        {
            using var response = await httpClient.GetAsync(url, cancellationToken);
            response.EnsureSuccessStatusCode();
            var templates = await ReadRequiredAsync<IReadOnlyList<MessageTemplateModel>>(
                response,
                cancellationToken);
            await cache.StoreTemplatesAsync(templates, cancellationToken);
            return templates;
        }
        catch (HttpRequestException)
        {
            var templates = await cache.GetTemplatesAsync(clientId, cancellationToken);
            return includeInactive ? templates : templates.Where(template => template.IsActive).ToArray();
        }
    }

    public async Task<MessageTemplateModel> SaveTemplateAsync(
        Guid? templateId,
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken)
    {
        using var response = templateId is null
            ? await httpClient.PostAsJsonAsync("api/message-templates", request, cancellationToken)
            : await httpClient.PutAsJsonAsync(
                $"api/message-templates/{templateId:D}",
                request,
                cancellationToken);
        response.EnsureSuccessStatusCode();
        var template = await ReadRequiredAsync<MessageTemplateModel>(response, cancellationToken);
        await cache.StoreTemplatesAsync([template], cancellationToken);
        return template;
    }

    public async Task<ClientCatalogTransferDocument> ExportAsync(
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync("api/clients/catalog/export", cancellationToken);
        response.EnsureSuccessStatusCode();
        return await ReadRequiredAsync<ClientCatalogTransferDocument>(response, cancellationToken);
    }

    public async Task<ClientCatalogImportResult> ImportAsync(
        ClientCatalogImportRequest request,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.PostAsJsonAsync(
            "api/clients/catalog/import",
            request,
            cancellationToken);
        response.EnsureSuccessStatusCode();
        return await ReadRequiredAsync<ClientCatalogImportResult>(response, cancellationToken);
    }

    public async Task<IReadOnlyList<AuditEventModel>> GetAuditAsync(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync(
            $"api/clients/{clientId:D}/audit?take=100",
            cancellationToken);
        response.EnsureSuccessStatusCode();
        return await ReadRequiredAsync<IReadOnlyList<AuditEventModel>>(response, cancellationToken);
    }

    private static async Task<T> ReadRequiredAsync<T>(
        HttpResponseMessage response,
        CancellationToken cancellationToken) =>
        await response.Content.ReadFromJsonAsync<T>(cancellationToken)
            ?? throw new InvalidOperationException("The client catalog response was empty.");
}
