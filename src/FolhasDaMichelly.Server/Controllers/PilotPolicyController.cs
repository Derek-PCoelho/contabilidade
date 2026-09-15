using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Contracts.Pilot;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;

namespace FolhasDaMichelly.Server.Controllers;

[ApiController]
[Route("api/pilot/policy")]
[Authorize]
[EnableRateLimiting("read")]
public sealed class PilotPolicyController(IConfiguration configuration) : ControllerBase
{
    [HttpGet]
    public ActionResult<PilotPolicyResponse> Get()
    {
        var environmentName = configuration["Phase11:EnvironmentName"]?.Trim() ?? string.Empty;
        var maximumClients = configuration.GetValue<int>("Phase11:MaximumClients");
        var minimumVersion = configuration["Phase10:MinimumSupportedVersion"] ?? AppVersionInfo.Current;
        var enabled = configuration.GetValue<bool>("Phase11:Enabled");
        var allowTest = configuration.GetValue<bool>("Phase11:AllowTest");
        var allowDraft = configuration.GetValue<bool>("Phase11:AllowDraft");
        var allowSend = configuration.GetValue<bool>("Phase11:AllowSend");
        var requireNonProductionData = configuration.GetValue<bool>("Phase11:RequireNonProductionData");
        if (!enabled ||
            !allowTest ||
            !allowDraft ||
            allowSend ||
            !requireNonProductionData ||
            !string.Equals(environmentName, "staging", StringComparison.OrdinalIgnoreCase) ||
            maximumClients is < 1 or > 5 ||
            !Version.TryParse(minimumVersion, out _))
        {
            return Problem(statusCode: StatusCodes.Status503ServiceUnavailable);
        }

        return Ok(new PilotPolicyResponse(
            enabled,
            environmentName,
            allowTest,
            allowDraft,
            allowSend,
            requireNonProductionData,
            maximumClients,
            minimumVersion));
    }
}
