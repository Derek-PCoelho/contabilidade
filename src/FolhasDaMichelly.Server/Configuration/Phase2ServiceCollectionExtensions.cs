using System.Security.Cryptography.X509Certificates;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Identity;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Identity;
using FolhasDaMichelly.Infrastructure.Common;
using FolhasDaMichelly.Infrastructure.Documents;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using FolhasDaMichelly.Server.Authentication;
using FolhasDaMichelly.Server.Hubs;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using OpenIddict.Abstractions;
using OpenIddict.Validation.AspNetCore;

namespace FolhasDaMichelly.Server.Configuration;

public static class ApiAuthenticationDefaults
{
    public const string Scheme = "Phase2Api";
}

public static class Phase2ServiceCollectionExtensions
{
    public static IServiceCollection AddPhase2Services(
        this IServiceCollection services,
        IConfiguration configuration,
        IHostEnvironment environment)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);
        ArgumentNullException.ThrowIfNull(environment);

        services.AddCentralPersistence(configuration, environment);
        services.AddPhase2Identity(configuration, environment);
        services.AddAuthorizationPolicies();
        services.AddHttpContextAccessor();
        services.AddScoped<IAuthenticatedUserAccessor, HttpAuthenticatedUserAccessor>();
        services.AddScoped<IDeviceSessionValidator, DeviceSessionValidator>();
        services.AddScoped<ISyncRepository, TenantSyncRepository>();
        services.AddScoped<IClientCatalogRepository, ClientCatalogRepository>();
        services.AddScoped<IClientResolver, CentralClientResolver>();
        services.AddScoped<ISyncChangePublisher, SignalRSyncChangePublisher>();
        services.AddSingleton<IClock, FolhasDaMichelly.Infrastructure.Common.SystemClock>();
        services.AddScoped<IAuthorizationHandler, ActiveDeviceSessionAuthorizationHandler>();
        services.AddHostedService<Phase2DatabaseInitializer>();
        return services;
    }

    private static void AddCentralPersistence(
        this IServiceCollection services,
        IConfiguration configuration,
        IHostEnvironment environment)
    {
        var provider = configuration["Database:Provider"] ?? "PostgreSql";
        services.AddDbContext<FolhasDbContext>(options =>
        {
            if (string.Equals(provider, "Sqlite", StringComparison.OrdinalIgnoreCase))
            {
                var sqliteConnection = configuration.GetConnectionString("CentralDatabase")
                    ?? "Data Source=folhas-central-development.db";
                options.UseSqlite(sqliteConnection);
            }
            else if (string.Equals(provider, "PostgreSql", StringComparison.OrdinalIgnoreCase))
            {
                var postgresConnection = configuration.GetConnectionString("CentralDatabase");
                if (string.IsNullOrWhiteSpace(postgresConnection) && environment.IsDevelopment())
                {
                    postgresConnection =
                        $"Host=127.0.0.1;Port=5432;Database=folhas_development;Username={Environment.UserName};Include Error Detail=false";
                }

                if (string.IsNullOrWhiteSpace(postgresConnection))
                {
                    throw new InvalidOperationException(
                        "ConnectionStrings:CentralDatabase is required outside Development.");
                }

                options.UseNpgsql(postgresConnection);
            }
            else
            {
                throw new InvalidOperationException($"Unsupported database provider: {provider}.");
            }

            options.UseOpenIddict<Guid>();
        });
    }

    private static void AddPhase2Identity(
        this IServiceCollection services,
        IConfiguration configuration,
        IHostEnvironment environment)
    {
        services
            .AddIdentity<ApplicationUser, ApplicationRole>(options =>
            {
                options.User.RequireUniqueEmail = true;
                options.SignIn.RequireConfirmedAccount = true;
                options.Password.RequiredLength = 12;
                options.Password.RequireDigit = true;
                options.Password.RequireLowercase = true;
                options.Password.RequireUppercase = true;
                options.Password.RequireNonAlphanumeric = true;
                options.Lockout.MaxFailedAccessAttempts = 5;
                options.Lockout.DefaultLockoutTimeSpan = TimeSpan.FromMinutes(15);
            })
            .AddEntityFrameworkStores<FolhasDbContext>()
            .AddDefaultTokenProviders();

        services.ConfigureApplicationCookie(options =>
        {
            options.Cookie.Name = "__Host-FolhasMichelly.Identity";
            options.Cookie.HttpOnly = true;
            options.Cookie.SameSite = SameSiteMode.Lax;
            options.Cookie.SecurePolicy = CookieSecurePolicy.Always;
            options.ExpireTimeSpan = TimeSpan.FromHours(8);
            options.LoginPath = "/account/login";
            options.SlidingExpiration = false;
        });

        services.AddOpenIddict()
            .AddCore(options =>
            {
                options.UseEntityFrameworkCore()
                    .UseDbContext<FolhasDbContext>()
                    .ReplaceDefaultEntities<Guid>();
            })
            .AddServer(options =>
            {
                options.SetAuthorizationEndpointUris("connect/authorize")
                    .SetEndSessionEndpointUris("connect/logout")
                    .SetRevocationEndpointUris("connect/revoke")
                    .SetTokenEndpointUris("connect/token");
                options.AllowAuthorizationCodeFlow();
                options.AllowRefreshTokenFlow();
                options.RequireProofKeyForCodeExchange();
                options.RegisterScopes(
                    OpenIddictConstants.Scopes.Email,
                    OpenIddictConstants.Scopes.OfflineAccess,
                    OpenIddictConstants.Scopes.OpenId,
                    OpenIddictConstants.Scopes.Profile,
                    OpenIddictConstants.Scopes.Roles,
                    "folhas_api");
                options.SetAccessTokenLifetime(TimeSpan.FromMinutes(10));
                options.SetRefreshTokenLifetime(TimeSpan.FromDays(30));

                if (environment.IsDevelopment() ||
                    string.Equals(environment.EnvironmentName, "Testing", StringComparison.Ordinal))
                {
                    options.AddEphemeralEncryptionKey();
                    options.AddEphemeralSigningKey();
                }
                else
                {
                    var signingCertificate = LoadProductionCertificate(
                        configuration,
                        "Signing");
                    var encryptionCertificate = LoadProductionCertificate(
                        configuration,
                        "Encryption");
                    options.AddSigningCertificate(signingCertificate);
                    options.AddEncryptionCertificate(encryptionCertificate);
                }

                options.UseAspNetCore()
                    .EnableAuthorizationEndpointPassthrough()
                    .EnableEndSessionEndpointPassthrough()
                    .EnableTokenEndpointPassthrough()
                    .EnableStatusCodePagesIntegration();
            })
            .AddValidation(options =>
            {
                options.UseLocalServer();
                options.EnableTokenEntryValidation();
                options.UseAspNetCore();
            });

        var developmentSchemeEnabled =
            (environment.IsDevelopment() ||
             string.Equals(environment.EnvironmentName, "Testing", StringComparison.Ordinal)) &&
            configuration.GetValue<bool>("Authentication:EnableDevelopmentScheme");

        services
            .AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = ApiAuthenticationDefaults.Scheme;
                options.DefaultChallengeScheme = ApiAuthenticationDefaults.Scheme;
            })
            .AddPolicyScheme(
                ApiAuthenticationDefaults.Scheme,
                ApiAuthenticationDefaults.Scheme,
                options => options.ForwardDefaultSelector = context =>
                    developmentSchemeEnabled &&
                    context.Request.Headers.ContainsKey(
                        DevelopmentAuthenticationHandler.UserHeader)
                        ? DevelopmentAuthenticationHandler.DevelopmentScheme
                        : OpenIddictValidationAspNetCoreDefaults.AuthenticationScheme)
            .AddScheme<DevelopmentAuthenticationOptions, DevelopmentAuthenticationHandler>(
                DevelopmentAuthenticationHandler.DevelopmentScheme,
                options => options.Enabled = developmentSchemeEnabled);
    }

    private static void AddAuthorizationPolicies(this IServiceCollection services)
    {
        services.AddAuthorization(options =>
        {
            AddPermissionPolicy(options, AuthorizationPolicies.ClientsRead, requireMfa: false);
            AddPermissionPolicy(options, AuthorizationPolicies.ClientsWrite, requireMfa: true);
            AddPermissionPolicy(options, TemplateAuthorizationPolicies.TemplatesRead, requireMfa: false);
            AddPermissionPolicy(options, TemplateAuthorizationPolicies.TemplatesWrite, requireMfa: true);
            AddPermissionPolicy(options, AuthorizationPolicies.UsersManage, requireMfa: true);
            AddPermissionPolicy(options, AuthorizationPolicies.EmailSend, requireMfa: true);
        });
    }

    private static X509Certificate2 LoadProductionCertificate(
        IConfiguration configuration,
        string purpose)
    {
        var path = configuration[$"Authentication:Oidc:{purpose}CertificatePath"];
        var password = configuration[$"Authentication:Oidc:{purpose}CertificatePassword"];
        if (string.IsNullOrWhiteSpace(path) || string.IsNullOrWhiteSpace(password) || !File.Exists(path))
        {
            throw new InvalidOperationException(
                $"Production OIDC {purpose.ToLowerInvariant()} certificate is not configured.");
        }

        var certificate = X509CertificateLoader.LoadPkcs12FromFile(
            path,
            password,
            X509KeyStorageFlags.DefaultKeySet);
        var now = DateTimeOffset.UtcNow;
        if (!certificate.HasPrivateKey || certificate.NotBefore.ToUniversalTime() > now.UtcDateTime ||
            certificate.NotAfter.ToUniversalTime() <= now.AddDays(30).UtcDateTime)
        {
            certificate.Dispose();
            throw new InvalidOperationException(
                $"Production OIDC {purpose.ToLowerInvariant()} certificate is invalid or expires within 30 days.");
        }

        return certificate;
    }

    private static void AddPermissionPolicy(
        AuthorizationOptions options,
        string permission,
        bool requireMfa)
    {
        options.AddPolicy(
            permission,
            policy =>
            {
                policy.RequireAuthenticatedUser();
                policy.RequireClaim(AppClaimNames.Permission, permission);
                policy.AddRequirements(new ActiveDeviceSessionRequirement());
                if (requireMfa)
                {
                    policy.RequireClaim(AppClaimNames.AuthenticationMethodReference, "mfa");
                }
            });
    }
}
