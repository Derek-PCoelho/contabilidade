using System.Security.Claims;
using System.Text.Encodings.Web;
using FolhasDaMichelly.Contracts.Identity;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;
using OpenIddict.Abstractions;

namespace FolhasDaMichelly.Server.Authentication;

public sealed class DevelopmentAuthenticationOptions : AuthenticationSchemeOptions
{
    public bool Enabled { get; set; }
}

public sealed class DevelopmentAuthenticationHandler(
    IOptionsMonitor<DevelopmentAuthenticationOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder)
    : AuthenticationHandler<DevelopmentAuthenticationOptions>(options, logger, encoder)
{
    public const string DevelopmentScheme = "DevelopmentBearer";
    public const string UserHeader = "X-Development-User";
    public const string OrganizationHeader = "X-Development-Organization";
    public const string DeviceHeader = "X-Development-Device";
    public const string PermissionsHeader = "X-Development-Permissions";
    public const string MultiFactorHeader = "X-Development-Mfa";
    public const string RolesHeader = "X-Development-Roles";

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (!Options.Enabled)
        {
            return Task.FromResult(AuthenticateResult.NoResult());
        }

        if (!Context.RequestServices.GetRequiredService<IHostEnvironment>().IsEnvironment("Testing") &&
            Context.Connection.RemoteIpAddress is { } remoteAddress &&
            !System.Net.IPAddress.IsLoopback(remoteAddress))
        {
            return Task.FromResult(AuthenticateResult.Fail(
                "Development authentication is restricted to loopback connections."));
        }

        if (!TryReadGuid(UserHeader, out var userId) ||
            !TryReadGuid(OrganizationHeader, out var organizationId) ||
            !TryReadGuid(DeviceHeader, out var deviceId))
        {
            return Task.FromResult(AuthenticateResult.NoResult());
        }

        var claims = new List<Claim>
        {
            new(OpenIddictConstants.Claims.Subject, userId.ToString("D")),
            new(ClaimTypes.NameIdentifier, userId.ToString("D")),
            new(AppClaimNames.OrganizationId, organizationId.ToString("D")),
            new(AppClaimNames.DeviceSessionId, deviceId.ToString("D")),
        };

        foreach (var permission in Request.Headers[PermissionsHeader]
                     .ToString()
                     .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            claims.Add(new Claim(AppClaimNames.Permission, permission));
        }

        foreach (var role in Request.Headers[RolesHeader]
                     .ToString()
                     .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            claims.Add(new Claim(ClaimTypes.Role, role));
            claims.Add(new Claim(OpenIddictConstants.Claims.Role, role));
        }

        if (string.Equals(
                Request.Headers[MultiFactorHeader].ToString(),
                "true",
                StringComparison.OrdinalIgnoreCase))
        {
            claims.Add(new Claim(AppClaimNames.AuthenticationMethodReference, "mfa"));
        }

        var identity = new ClaimsIdentity(
            claims,
            DevelopmentScheme,
            ClaimTypes.Name,
            ClaimTypes.Role);
        var ticket = new AuthenticationTicket(
            new ClaimsPrincipal(identity),
            DevelopmentScheme);
        return Task.FromResult(AuthenticateResult.Success(ticket));
    }

    private bool TryReadGuid(string headerName, out Guid value) =>
        Guid.TryParse(Request.Headers[headerName].ToString(), out value) && value != Guid.Empty;
}
