using System.Net.Http.Headers;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Application.Incidents;
using FolhasDaMichelly.Application.Pilot;
using FolhasDaMichelly.Application.Preferences;
using FolhasDaMichelly.Application.Production;
using FolhasDaMichelly.Application.Security;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Infrastructure.Clients;
using FolhasDaMichelly.Infrastructure.Common;
using FolhasDaMichelly.Infrastructure.Dispatch;
using FolhasDaMichelly.Infrastructure.Documents;
using FolhasDaMichelly.Infrastructure.Incidents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using FolhasDaMichelly.Infrastructure.Security;
using FolhasDaMichelly.Infrastructure.Sync;
using FolhasDaMichelly.Infrastructure.Updates;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using OpenIddict.Abstractions;
using OpenIddict.Client;

namespace FolhasDaMichelly.Infrastructure.Identity;

public static class DesktopPhase2ServiceCollectionExtensions
{
    public static IServiceCollection AddDesktopPhase2(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        ArgumentNullException.ThrowIfNull(services);
        ArgumentNullException.ThrowIfNull(configuration);

        var dataDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "FolhasDaMichelly");
        Directory.CreateDirectory(dataDirectory);
        var configuredApiBaseAddress = configuration["Phase2:ApiBaseAddress"];
        var connectedApiEnabled = !string.IsNullOrWhiteSpace(configuredApiBaseAddress);

        services.AddSingleton<IClock, SystemClock>();
        services.TryAddSingleton<ISecretStore>(_ => new NativeSecretStore(dataDirectory));
        services.AddSingleton(new LocalStoragePermissions(dataDirectory));
        services.AddSingleton<IProtectedBackupService, AesGcmProtectedBackupService>();
        services.AddSingleton<ISensitiveTextRedactor, SensitiveTextRedactor>();
        services.AddSingleton(new VelopackAppUpdateOptions
        {
            FeedBaseUrl = configuration["Phase10:UpdateFeedBaseUrl"] ?? string.Empty,
            AllowLocalFeed = bool.TryParse(
                configuration["Phase10:AllowLocalUpdateFeed"],
                out var allowLocalUpdateFeed) && allowLocalUpdateFeed,
            MaximumDeltasBeforeFallback = int.TryParse(
                configuration["Phase10:MaximumDeltasBeforeFallback"],
                out var maximumDeltas) && maximumDeltas is >= 0 and <= 10
                ? maximumDeltas
                : 3,
        });
        services.AddSingleton<IAppUpdateService, VelopackAppUpdateService>();
        services.AddTransient<BearerTokenHandler>();
        services.AddScoped<ILocalSyncStore, SqliteLocalSyncStore>();
        services.AddScoped<IClientCatalogCache, SqliteClientCatalogCache>();
        services.AddScoped<IDocumentRecognitionCache, SqliteDocumentRecognitionCache>();
        services.AddScoped<IDocumentReviewStore, SqliteDocumentReviewStore>();
        services.AddScoped<IDispatchWorkflowStore, SqliteDispatchWorkflowStore>();
        services.AddScoped<IIncidentStore, SqliteIncidentStore>();
        services.AddScoped<IWorkspacePreferencesStore, SqliteWorkspacePreferencesStore>();
        services.AddScoped<IPilotChecklistStore, SqlitePilotChecklistStore>();
        services.AddScoped<IPilotReadinessService, PilotReadinessService>();
        if (connectedApiEnabled)
        {
            services.AddSingleton<JwtDocumentReviewContextAccessor>();
            services.AddSingleton<IDocumentReviewContextAccessor>(provider =>
                provider.GetRequiredService<JwtDocumentReviewContextAccessor>());
            services.AddSingleton<IDispatchExecutionContextAccessor>(provider =>
                provider.GetRequiredService<JwtDocumentReviewContextAccessor>());
        }
        else
        {
            services.AddSingleton<LocalDesktopOperationContextAccessor>();
            services.AddSingleton<IDocumentReviewContextAccessor>(provider =>
                provider.GetRequiredService<LocalDesktopOperationContextAccessor>());
            services.AddSingleton<IDispatchExecutionContextAccessor>(provider =>
                provider.GetRequiredService<LocalDesktopOperationContextAccessor>());
        }
        services.AddSingleton<IPdfTextExtractor, PdfPigTextExtractor>();
        services.AddSingleton<IDocumentClassifier, DeterministicDocumentClassifier>();
        services.AddSingleton<IDocumentParser, ProfileDocumentParser>();
        services.AddSingleton<IDocumentPeriodParser, DocumentPeriodParser>();
        services.AddSingleton(new DocumentRecognitionOptions());
        services.AddSingleton(new DocumentReviewOptions());
        var dispatchOptions = new DispatchWorkflowOptions();
        if (bool.TryParse(configuration["Phase6:EmailSendEnabled"], out var emailSendEnabled))
        {
            dispatchOptions.EmailSendEnabled = emailSendEnabled;
        }

        var pilotOptions = new PilotModeOptions
        {
            Enabled = bool.TryParse(configuration["Phase11:Enabled"], out var pilotEnabled) && pilotEnabled,
            EnvironmentName = configuration["Phase11:EnvironmentName"]?.Trim() ?? "staging",
            AllowTest = !bool.TryParse(configuration["Phase11:AllowTest"], out var pilotAllowTest) || pilotAllowTest,
            AllowDraft = !bool.TryParse(configuration["Phase11:AllowDraft"], out var pilotAllowDraft) || pilotAllowDraft,
            AllowSend = bool.TryParse(configuration["Phase11:AllowSend"], out var pilotAllowSend) && pilotAllowSend,
            RequireNonProductionData = !bool.TryParse(
                configuration["Phase11:RequireNonProductionData"],
                out var requireNonProductionData) || requireNonProductionData,
            MaximumClients = int.TryParse(configuration["Phase11:MaximumClients"], out var maximumClients)
                ? maximumClients
                : 5,
        };
        dispatchOptions.PilotModeEnabled = pilotOptions.Enabled;
        dispatchOptions.PilotAllowTest = pilotOptions.AllowTest;
        dispatchOptions.PilotAllowDraft = pilotOptions.AllowDraft;
        dispatchOptions.PilotAllowSend = pilotOptions.AllowSend;

        var productionOptions = new ProductionRolloutOptions
        {
            EnforceForExternalSend = true,
            Enabled = bool.TryParse(configuration["Phase12:Enabled"], out var productionEnabled) && productionEnabled,
            EnvironmentName = configuration["Phase12:EnvironmentName"]?.Trim() ?? "production",
            Stage = Enum.TryParse<ProductionRolloutStage>(
                configuration["Phase12:Stage"],
                ignoreCase: true,
                out var productionStage)
                ? productionStage
                : ProductionRolloutStage.Closed,
            PilotApproved = bool.TryParse(configuration["Phase12:PilotApproved"], out var pilotApproved) && pilotApproved,
            AllowSend = bool.TryParse(configuration["Phase12:AllowSend"], out var productionAllowSend) && productionAllowSend,
            ExternalProviderSendEnabled =
                bool.TryParse(
                    configuration["Phase7:MicrosoftGraph:EmailSendEnabled"],
                    out var productionGraphSendEnabled) && productionGraphSendEnabled ||
                bool.TryParse(
                    configuration["Phase8:Gmail:EmailSendEnabled"],
                    out var productionGmailSendEnabled) && productionGmailSendEnabled,
            StableReleaseApproved = bool.TryParse(
                configuration["Phase12:StableReleaseApproved"],
                out var stableReleaseApproved) && stableReleaseApproved,
            BackupRestoreDrillCompleted = bool.TryParse(
                configuration["Phase12:BackupRestoreDrillCompleted"],
                out var backupDrillCompleted) && backupDrillCompleted,
            MonitoringReady = bool.TryParse(configuration["Phase12:MonitoringReady"], out var monitoringReady) && monitoringReady,
            IncidentResponseReady = bool.TryParse(
                configuration["Phase12:IncidentResponseReady"],
                out var incidentResponseReady) && incidentResponseReady,
            SupportReady = bool.TryParse(configuration["Phase12:SupportReady"], out var supportReady) && supportReady,
            MaximumBatchSize = int.TryParse(configuration["Phase12:MaximumBatchSize"], out var maximumBatchSize)
                ? maximumBatchSize
                : 5,
            MaximumDailySends = int.TryParse(configuration["Phase12:MaximumDailySends"], out var maximumDailySends)
                ? maximumDailySends
                : 20,
            MinimumApplicationVersion = configuration["Phase12:MinimumApplicationVersion"] ?? AppVersionInfo.Current,
        };
        var configuredRoles = (configuration["Phase12:AllowedRoles"] ?? string.Empty)
            .Split([',', ';'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .ToHashSet(StringComparer.Ordinal);
        if (configuredRoles.Count > 0)
        {
            productionOptions.AllowedRoles = configuredRoles;
        }

        var productionSnapshot = ProductionReadinessEvaluator.Evaluate(
            productionOptions,
            pilotOptions.Enabled,
            bool.TryParse(configuration["Phase10:EmailSendEnabled"], out var globalSendEnabled) && globalSendEnabled,
            bool.TryParse(configuration["Phase10:StableChannelEnabled"], out var stableChannelEnabled) && stableChannelEnabled);
        dispatchOptions.ProductionRolloutEnforced = productionOptions.EnforceForExternalSend;
        dispatchOptions.ProductionRolloutReady = productionSnapshot.IsReadyForSend;
        dispatchOptions.ProductionMaximumBatchSize = productionOptions.MaximumBatchSize;

        var graphOptions = new MicrosoftGraphOptions
        {
            Enabled = bool.TryParse(configuration["Phase7:MicrosoftGraph:Enabled"], out var graphEnabled) && graphEnabled,
            ClientId = configuration["Phase7:MicrosoftGraph:ClientId"] ?? string.Empty,
            TenantId = configuration["Phase7:MicrosoftGraph:TenantId"] ?? "organizations",
            RedirectUri = configuration["Phase7:MicrosoftGraph:RedirectUri"] ?? "http://localhost",
            ControlledRecipient = configuration["Phase7:MicrosoftGraph:ControlledRecipient"] ?? string.Empty,
        };
        if (int.TryParse(configuration["Phase7:MicrosoftGraph:MaximumRetryAttempts"], out var retryAttempts) &&
            retryAttempts is >= 0 and <= 5)
        {
            graphOptions.MaximumRetryAttempts = retryAttempts;
        }

        dispatchOptions.MicrosoftGraphEnabled = graphOptions.Enabled;
        dispatchOptions.MicrosoftGraphControlledRecipient = graphOptions.ControlledRecipient;
        dispatchOptions.MicrosoftGraphSendEnabled =
            bool.TryParse(configuration["Phase7:MicrosoftGraph:EmailSendEnabled"], out var graphSendEnabled) &&
            graphSendEnabled;
        graphOptions.EmailSendEnabled = dispatchOptions.MicrosoftGraphSendEnabled;

        var gmailOptions = new GmailOptions
        {
            Enabled = bool.TryParse(configuration["Phase8:Gmail:Enabled"], out var gmailEnabled) && gmailEnabled,
            ClientId = configuration["Phase8:Gmail:ClientId"] ?? string.Empty,
            ControlledRecipient = configuration["Phase8:Gmail:ControlledRecipient"] ?? string.Empty,
        };
        if (int.TryParse(configuration["Phase8:Gmail:MaximumRetryAttempts"], out var gmailRetryAttempts) &&
            gmailRetryAttempts is >= 0 and <= 5)
        {
            gmailOptions.MaximumRetryAttempts = gmailRetryAttempts;
        }

        dispatchOptions.ProviderKey = configuration["Phase8:ProviderKey"]?.Trim() ??
            configuration["Phase7:ProviderKey"]?.Trim() ??
            DispatchWorkflowOptions.FakeProviderKey;
        var minimumVersionKey = dispatchOptions.ProviderKey switch
        {
            DispatchWorkflowOptions.GmailProviderKey => "Phase8:MinimumSendVersion",
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => "Phase7:MinimumSendVersion",
            _ => "Phase6:MinimumSendVersion",
        };
        if (!string.IsNullOrWhiteSpace(configuration[minimumVersionKey]))
        {
            dispatchOptions.MinimumSendVersion = configuration[minimumVersionKey]!;
        }

        dispatchOptions.GmailEnabled = gmailOptions.Enabled;
        dispatchOptions.GmailControlledRecipient = gmailOptions.ControlledRecipient;
        dispatchOptions.GmailSendEnabled =
            bool.TryParse(configuration["Phase8:Gmail:EmailSendEnabled"], out var gmailSendEnabled) &&
            gmailSendEnabled;
        gmailOptions.EmailSendEnabled = dispatchOptions.GmailSendEnabled;

        services.AddSingleton(dispatchOptions);
        services.AddSingleton(pilotOptions);
        services.AddSingleton(productionOptions);
        services.AddSingleton(productionSnapshot);
        services.AddSingleton(graphOptions);
        services.AddSingleton(gmailOptions);
        services.AddSingleton(new FakeEmailProviderOptions
        {
            OutputDirectory = Path.Combine(dataDirectory, "FakeOutbox"),
        });
        services.AddSingleton<IValidationRule<DocumentValidationContext>, FileIntegrityValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, RecognitionValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, ClientResolutionValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, ProfileRequiredFieldsValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, PeriodValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, AmountValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, EmployerRootConsistencyValidationRule>();
        services.AddSingleton<IValidationRule<DocumentValidationContext>, DueDateValidationRule>();
        services.AddScoped<IDocumentRecognitionService, DocumentRecognitionService>();
        services.AddScoped<IDocumentReviewService, DocumentReviewService>();
        services.AddScoped<IDispatchMessageComposer, DeterministicDispatchMessageComposer>();
        services.AddScoped<IDispatchWorkflowService, DispatchWorkflowService>();
        services.AddScoped<IIncidentService, IncidentService>();
        services.AddSingleton<FakeEmailProvider>();
        services.AddSingleton<FakeEmailAccountSession>();
        services.AddSingleton<MicrosoftGraphEmailAccountSession>();
        services.AddSingleton<IGmailOAuthAuthorizationReceiver, SystemBrowserGmailOAuthAuthorizationReceiver>();
        services.AddHttpClient<GmailEmailAccountSession>();
        services.AddScoped<IEmailAccountSession>(provider => dispatchOptions.ProviderKey switch
        {
            DispatchWorkflowOptions.FakeProviderKey => provider.GetRequiredService<FakeEmailAccountSession>(),
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => provider.GetRequiredService<MicrosoftGraphEmailAccountSession>(),
            DispatchWorkflowOptions.GmailProviderKey => provider.GetRequiredService<GmailEmailAccountSession>(),
            _ => throw new InvalidOperationException("Provedor de conta de e-mail configurado não autorizado."),
        });
        services.AddScoped<IEmailAccountConnectionService, EmailAccountConnectionService>();
        services.AddHttpClient<MicrosoftGraphEmailProvider>(client => client.BaseAddress = graphOptions.ApiBaseAddress);
        services.AddHttpClient<GmailEmailProvider>(client => client.BaseAddress = gmailOptions.ApiBaseAddress);
        services.AddScoped<IEmailProvider>(provider => dispatchOptions.ProviderKey switch
        {
            DispatchWorkflowOptions.FakeProviderKey => provider.GetRequiredService<FakeEmailProvider>(),
            DispatchWorkflowOptions.MicrosoftGraphProviderKey => provider.GetRequiredService<MicrosoftGraphEmailProvider>(),
            DispatchWorkflowOptions.GmailProviderKey => provider.GetRequiredService<GmailEmailProvider>(),
            _ => throw new InvalidOperationException("Provedor de e-mail configurado não autorizado."),
        });
        services.AddSingleton<IDispatchReportExporter, ClosedXmlDispatchReportExporter>();
        services.AddScoped<ISyncService, SyncService>();
        services.AddScoped<IDesktopAuthenticationService, DesktopAuthenticationService>();
        services.AddDbContext<LocalCacheDbContext>(options =>
            options.UseSqlite($"Data Source={Path.Combine(dataDirectory, "cache.db")}"));

        var oidcConnection = new SqliteConnection("Data Source=:memory:");
        oidcConnection.Open();
        services.AddSingleton(oidcConnection);
        services.AddDbContext<OidcClientDbContext>(options =>
        {
            options.UseSqlite(oidcConnection);
            options.UseOpenIddict<Guid>();
        });

        var authority = new Uri(
            configuration["Phase2:Authority"] ?? "https://localhost:7001/",
            UriKind.Absolute);
        services.AddOpenIddict()
            .AddCore(options => options.UseEntityFrameworkCore()
                .UseDbContext<OidcClientDbContext>()
                .ReplaceDefaultEntities<Guid>())
            .AddClient(options =>
            {
                options.AllowAuthorizationCodeFlow();
                options.AllowRefreshTokenFlow();
                options.AddEphemeralEncryptionKey();
                options.AddEphemeralSigningKey();
                options.UseSystemIntegration();
                options.UseSystemNetHttp()
                    .SetProductInformation(typeof(DesktopAuthenticationService).Assembly);
                options.AddRegistration(
                    new OpenIddictClientRegistration
                    {
                        ClientId = "folhas-desktop",
                        Issuer = authority,
                        RedirectUri = new Uri("/", UriKind.Relative),
                        Scopes =
                        {
                            OpenIddictConstants.Scopes.OpenId,
                            OpenIddictConstants.Scopes.OfflineAccess,
                            OpenIddictConstants.Scopes.Email,
                            OpenIddictConstants.Scopes.Profile,
                            OpenIddictConstants.Scopes.Roles,
                            "folhas_api",
                        },
                    });
            });

        var apiBaseAddress = new Uri(
            configuredApiBaseAddress ?? authority.ToString(),
            UriKind.Absolute);
        services.AddHttpClient<ISyncTransport, HttpSyncTransport>(client =>
                client.BaseAddress = apiBaseAddress)
            .AddHttpMessageHandler<BearerTokenHandler>();
        if (connectedApiEnabled)
        {
            services.AddHttpClient<IClientCatalogService, HttpClientCatalogService>(client =>
                    client.BaseAddress = apiBaseAddress)
                .AddHttpMessageHandler<BearerTokenHandler>();
            services.AddHttpClient<IClientResolver, HttpClientResolver>(client =>
                    client.BaseAddress = apiBaseAddress)
                .AddHttpMessageHandler<BearerTokenHandler>();
        }
        else
        {
            services.AddScoped<IClientCatalogService, SqliteLocalClientCatalogService>();
            services.AddScoped<ILocalClientCatalogMaintenance>(provider =>
                (ILocalClientCatalogMaintenance)provider.GetRequiredService<IClientCatalogService>());
            services.AddScoped<IClientResolver, SqliteLocalClientResolver>();
        }

        services.AddHttpClient<IRemoteEmailSendGuard, HttpRemoteEmailSendGuard>(client =>
                client.BaseAddress = apiBaseAddress)
            .AddHttpMessageHandler<BearerTokenHandler>();
        services.AddSingleton<ISyncNotificationClient>(provider =>
            new SignalRSyncNotificationClient(
                new Uri(apiBaseAddress, "hubs/sync"),
                () => provider.GetRequiredService<ISecretStore>()
                    .RetrieveAsync("app-session/access-token", CancellationToken.None)));
        services.AddHostedService<DesktopDatabaseInitializer>();
        return services;
    }
}

public sealed class BearerTokenHandler(ISecretStore secretStore) : DelegatingHandler
{
    protected override async Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request,
        CancellationToken cancellationToken)
    {
        var token = await secretStore.RetrieveAsync(
            "app-session/access-token",
            cancellationToken);
        if (!string.IsNullOrWhiteSpace(token))
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }

        return await base.SendAsync(request, cancellationToken);
    }
}

public sealed class DesktopDatabaseInitializer(
    IServiceScopeFactory scopeFactory,
    LocalStoragePermissions storagePermissions) : IHostedService
{
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        await using var scope = scopeFactory.CreateAsyncScope();
        await scope.ServiceProvider.GetRequiredService<OidcClientDbContext>()
            .Database.EnsureCreatedAsync(cancellationToken);
        await scope.ServiceProvider.GetRequiredService<LocalCacheDbContext>()
            .Database.MigrateAsync(cancellationToken);
        storagePermissions.Apply();
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}
