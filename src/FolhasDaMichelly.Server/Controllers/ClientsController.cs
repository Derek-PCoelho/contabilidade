using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Sync;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/clients")]
public sealed class ClientsController(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    IClientCatalogRepository repository,
    ISyncChangePublisher changePublisher) : ControllerBase
{
    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("read")]
    [HttpGet]
    public Task<ClientSearchResponse> Search(
        [FromQuery] string? search = null,
        [FromQuery] bool? isActive = null,
        [FromQuery] PersonTypeModel? personType = null,
        [FromQuery] int skip = 0,
        [FromQuery] int take = 50,
        CancellationToken cancellationToken = default) =>
        repository.SearchAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            search,
            isActive,
            personType,
            skip,
            take,
            cancellationToken);

    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("read")]
    [HttpGet("{clientId:guid}")]
    public async Task<ActionResult<ClientDetails>> Get(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var result = await repository.GetAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            clientId,
            cancellationToken);
        return result is null ? NotFound() : Ok(result);
    }

    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("read")]
    [HttpGet("{clientId:guid}/readiness")]
    public async Task<ActionResult<ClientReadinessResponse>> GetReadiness(
        Guid clientId,
        CancellationToken cancellationToken)
    {
        var result = await repository.GetReadinessAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            clientId,
            DateOnly.FromDateTime(DateTime.UtcNow),
            cancellationToken);
        return result is null ? NotFound() : Ok(result);
    }

    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("read")]
    [HttpGet("{clientId:guid}/audit")]
    public Task<IReadOnlyList<AuditEventModel>> GetAudit(
        Guid clientId,
        [FromQuery] int take = 50,
        CancellationToken cancellationToken = default) =>
        repository.GetClientAuditAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            clientId,
            take,
            cancellationToken);

    [Authorize(Policy = AuthorizationPolicies.ClientsWrite)]
    [EnableRateLimiting("write")]
    [HttpPost]
    public async Task<ActionResult<ClientDetails>> Create(
        ClientMutationRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            var user = authenticatedUserAccessor.Current;
            var result = await repository.CreateAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                request,
                Guid.NewGuid(),
                cancellationToken);
            await PublishAsync(user.OrganizationId, cancellationToken);
            return CreatedAtAction(nameof(Get), new { clientId = result.Id }, result);
        }
        catch (CatalogValidationException exception)
        {
            return ValidationProblem(exception.Message);
        }
    }

    [Authorize(Policy = AuthorizationPolicies.ClientsWrite)]
    [EnableRateLimiting("write")]
    [HttpPut("{clientId:guid}")]
    public async Task<ActionResult<ClientDetails>> Update(
        Guid clientId,
        ClientMutationRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            var user = authenticatedUserAccessor.Current;
            var result = await repository.UpdateAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                clientId,
                request,
                Guid.NewGuid(),
                cancellationToken);
            if (result is null)
            {
                return NotFound();
            }

            await PublishAsync(user.OrganizationId, cancellationToken);
            return Ok(result);
        }
        catch (CatalogConcurrencyException exception)
        {
            return ConflictProblem(exception.Message);
        }
        catch (CatalogValidationException exception)
        {
            return ValidationProblem(exception.Message);
        }
    }

    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [Authorize(Policy = TemplateAuthorizationPolicies.TemplatesRead)]
    [EnableRateLimiting("sensitive")]
    [HttpGet("catalog/export")]
    public Task<ClientCatalogTransferDocument> Export(CancellationToken cancellationToken) =>
        repository.ExportAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            cancellationToken);

    [Authorize(Policy = AuthorizationPolicies.ClientsWrite)]
    [Authorize(Policy = TemplateAuthorizationPolicies.TemplatesWrite)]
    [EnableRateLimiting("sensitive")]
    [HttpPost("catalog/import")]
    public async Task<ActionResult<ClientCatalogImportResult>> Import(
        ClientCatalogImportRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            var user = authenticatedUserAccessor.Current;
            var result = await repository.ImportAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                request,
                Guid.NewGuid(),
                cancellationToken);
            if (!request.DryRun)
            {
                await PublishAsync(user.OrganizationId, cancellationToken);
            }

            return Ok(result);
        }
        catch (CatalogValidationException exception)
        {
            return ValidationProblem(exception.Message);
        }
    }

    private async Task PublishAsync(Guid organizationId, CancellationToken cancellationToken)
    {
        var checkpoint = await repository.GetLatestCheckpointAsync(
            organizationId,
            cancellationToken);
        await changePublisher.PublishAsync(
            organizationId,
            new SyncNotification(checkpoint, "client-catalog"),
            cancellationToken);
    }

    private BadRequestObjectResult ValidationProblem(string detail) =>
        BadRequest(new ProblemDetails
        {
            Title = "Invalid client catalog data",
            Detail = detail,
            Status = StatusCodes.Status400BadRequest,
        });

    private ConflictObjectResult ConflictProblem(string detail) =>
        Conflict(new ProblemDetails
        {
            Title = "Concurrent catalog update",
            Detail = detail,
            Status = StatusCodes.Status409Conflict,
        });
}
