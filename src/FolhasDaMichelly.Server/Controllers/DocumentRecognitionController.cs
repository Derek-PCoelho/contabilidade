using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Contracts.Documents;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/document-recognition")]
public sealed class DocumentRecognitionController(IClientResolver clientResolver) : ControllerBase
{
    [Authorize(Policy = AuthorizationPolicies.ClientsRead)]
    [EnableRateLimiting("write")]
    [HttpPost("resolve-client")]
    public async Task<ActionResult<ClientResolutionResult>> ResolveClient(
        ClientResolutionRequest request,
        CancellationToken cancellationToken)
    {
        if (request.Fields.Count > 50 || request.Fields.Any(field =>
                field.Name.Length > 100 ||
                field.Value.Length > 500 ||
                field.DisplayValue.Length > 500 ||
                field.Evidence.Snippet.Length > 1_000))
        {
            return BadRequest(new ProblemDetails
            {
                Title = "Invalid recognition resolution request",
                Detail = "The semantic field payload exceeds the allowed limits.",
                Status = StatusCodes.Status400BadRequest,
            });
        }

        return Ok(await clientResolver.ResolveAsync(request, cancellationToken));
    }
}
