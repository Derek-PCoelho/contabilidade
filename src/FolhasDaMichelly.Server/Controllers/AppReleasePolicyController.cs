using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Contracts.Updates;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/app-release-policy")]
[Authorize]
[EnableRateLimiting("read")]
public sealed class AppReleasePolicyController(IConfiguration configuration) : ControllerBase
{
    [HttpGet]
    public ActionResult<AppReleasePolicyResponse> Get([FromQuery] string currentVersion)
    {
        if (string.IsNullOrWhiteSpace(currentVersion) ||
            currentVersion.Length > 32 ||
            !Version.TryParse(currentVersion, out var current))
        {
            return BadRequest();
        }

        var minimumText = configuration["Phase10:MinimumSupportedVersion"] ?? AppVersionInfo.Current;
        if (!Version.TryParse(minimumText, out var minimum))
        {
            return Problem(statusCode: StatusCodes.Status503ServiceUnavailable);
        }

        return Ok(new AppReleasePolicyResponse(
            minimumText,
            current >= minimum,
            configuration.GetValue<bool>("Phase10:EmailSendEnabled"),
            configuration.GetValue<bool>("Phase10:BetaChannelEnabled"),
            configuration.GetValue<bool>("Phase10:StableChannelEnabled")));
    }
}
