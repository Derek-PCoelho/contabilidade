using System.Security.Claims;
using FolhasDaMichelly.Contracts.Identity;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using OpenIddict.Abstractions;
using static OpenIddict.Abstractions.OpenIddictConstants;

namespace FolhasDaMichelly.Server.Configuration;

public sealed class Phase2DatabaseInitializer(
    IServiceScopeFactory scopeFactory,
    IConfiguration configuration) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (!configuration.GetValue<bool>("Database:Initialize"))
        {
            return;
        }

        await using var scope = scopeFactory.CreateAsyncScope();
        var dbContext = scope.ServiceProvider.GetRequiredService<FolhasDbContext>();
        await dbContext.Database.MigrateAsync(cancellationToken);
        await SeedRolesAsync(scope.ServiceProvider, cancellationToken);
        await SeedOidcClientAsync(scope.ServiceProvider, cancellationToken);
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    private static async Task SeedRolesAsync(
        IServiceProvider services,
        CancellationToken cancellationToken)
    {
        var roleManager = services.GetRequiredService<RoleManager<ApplicationRole>>();
        foreach (var roleName in AppRoles.All)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var role = await roleManager.FindByNameAsync(roleName);
            if (role is null)
            {
                role = new ApplicationRole
                {
                    Id = Guid.NewGuid(),
                    Name = roleName,
                };
                var created = await roleManager.CreateAsync(role);
                EnsureSucceeded(created, $"creating role {roleName}");
            }

            var existingClaims = await roleManager.GetClaimsAsync(role);
            var existingPermissions = existingClaims
                .Where(claim => claim.Type == AppClaimNames.Permission)
                .Select(claim => claim.Value)
                .ToHashSet(StringComparer.Ordinal);
            foreach (var permission in RolePermissionCatalog.ForRole(roleName))
            {
                if (existingPermissions.Add(permission))
                {
                    var added = await roleManager.AddClaimAsync(
                        role,
                        new Claim(AppClaimNames.Permission, permission));
                    EnsureSucceeded(added, $"adding {permission} to {roleName}");
                }
            }
        }
    }

    private static async Task SeedOidcClientAsync(
        IServiceProvider services,
        CancellationToken cancellationToken)
    {
        var manager = services.GetRequiredService<IOpenIddictApplicationManager>();
        if (await manager.FindByClientIdAsync("folhas-desktop", cancellationToken) is not null)
        {
            return;
        }

        var descriptor = new OpenIddictApplicationDescriptor
        {
            ClientId = "folhas-desktop",
            ClientType = ClientTypes.Public,
            ConsentType = ConsentTypes.Implicit,
            DisplayName = "Folhas da Michelly Desktop",
        };
        descriptor.RedirectUris.Add(new Uri("http://localhost/"));
        descriptor.RedirectUris.Add(new Uri("com.danziatus.folhasdamichelly:/callback"));
        descriptor.PostLogoutRedirectUris.Add(new Uri("http://localhost/"));
        descriptor.PostLogoutRedirectUris.Add(
            new Uri("com.danziatus.folhasdamichelly:/signout-callback"));
        descriptor.Permissions.UnionWith(
        [
            Permissions.Endpoints.Authorization,
            Permissions.Endpoints.EndSession,
            Permissions.Endpoints.Revocation,
            Permissions.Endpoints.Token,
            Permissions.GrantTypes.AuthorizationCode,
            Permissions.GrantTypes.RefreshToken,
            Permissions.ResponseTypes.Code,
            Permissions.Scopes.Email,
            Permissions.Scopes.Profile,
            Permissions.Scopes.Roles,
            Permissions.Prefixes.Scope + "folhas_api",
        ]);
        descriptor.Requirements.Add(Requirements.Features.ProofKeyForCodeExchange);
        await manager.CreateAsync(descriptor, cancellationToken);
    }

    private static void EnsureSucceeded(IdentityResult result, string operation)
    {
        if (!result.Succeeded)
        {
            throw new InvalidOperationException(
                $"Identity failed while {operation}: " +
                string.Join("; ", result.Errors.Select(error => error.Code)));
        }
    }
}
