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
[Route("api/message-templates")]
public sealed class MessageTemplatesController(
    IAuthenticatedUserAccessor authenticatedUserAccessor,
    IClientCatalogRepository repository,
    ISyncChangePublisher changePublisher) : ControllerBase
{
    [Authorize(Policy = TemplateAuthorizationPolicies.TemplatesRead)]
    [EnableRateLimiting("read")]
    [HttpGet]
    public Task<IReadOnlyList<MessageTemplateModel>> GetAll(
        [FromQuery] Guid? clientId = null,
        [FromQuery] bool includeInactive = false,
        CancellationToken cancellationToken = default) =>
        repository.GetTemplatesAsync(
            authenticatedUserAccessor.Current.OrganizationId,
            clientId,
            includeInactive,
            cancellationToken);

    [Authorize(Policy = TemplateAuthorizationPolicies.TemplatesWrite)]
    [EnableRateLimiting("write")]
    [HttpPost]
    public async Task<ActionResult<MessageTemplateModel>> Create(
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            var user = authenticatedUserAccessor.Current;
            var result = await repository.CreateTemplateAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                request,
                Guid.NewGuid(),
                cancellationToken);
            await PublishAsync(user.OrganizationId, cancellationToken);
            return Created($"/api/message-templates/{result.Id:D}", result);
        }
        catch (CatalogValidationException exception)
        {
            return ValidationProblem(exception.Message);
        }
    }

    [Authorize(Policy = TemplateAuthorizationPolicies.TemplatesWrite)]
    [EnableRateLimiting("write")]
    [HttpPut("{templateId:guid}")]
    public async Task<ActionResult<MessageTemplateModel>> Update(
        Guid templateId,
        MessageTemplateMutationRequest request,
        CancellationToken cancellationToken)
    {
        try
        {
            var user = authenticatedUserAccessor.Current;
            var result = await repository.UpdateTemplateAsync(
                user.OrganizationId,
                user.UserId,
                user.DeviceSessionId,
                templateId,
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
            return Conflict(new ProblemDetails
            {
                Title = "Concurrent template update",
                Detail = exception.Message,
                Status = StatusCodes.Status409Conflict,
            });
        }
        catch (CatalogValidationException exception)
        {
            return ValidationProblem(exception.Message);
        }
    }

    private async Task PublishAsync(Guid organizationId, CancellationToken cancellationToken)
    {
        var checkpoint = await repository.GetLatestCheckpointAsync(organizationId, cancellationToken);
        await changePublisher.PublishAsync(
            organizationId,
            new SyncNotification(checkpoint, "message-template"),
            cancellationToken);
    }

    private BadRequestObjectResult ValidationProblem(string detail) =>
        BadRequest(new ProblemDetails
        {
            Title = "Invalid message template",
            Detail = detail,
            Status = StatusCodes.Status400BadRequest,
        });
}
