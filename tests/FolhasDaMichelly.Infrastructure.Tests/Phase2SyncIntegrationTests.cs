using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Sync;
using FolhasDaMichelly.Contracts.Sync;
using FolhasDaMichelly.Domain.Organizations;
using FolhasDaMichelly.Infrastructure.Common;
using FolhasDaMichelly.Infrastructure.Identity;
using FolhasDaMichelly.Infrastructure.Persistence.Central;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase2SyncIntegrationTests
{
    private static readonly DateTimeOffset Now = new(2026, 8, 20, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public async Task TwoClientsSynchronizeWithConflictIdempotencyAndTenantIsolation()
    {
        await using var centralConnection = OpenMemoryConnection();
        await using var central = CreateCentralContext(centralConnection);
        await central.Database.EnsureCreatedAsync();
        var clock = new TestClock(Now);
        var repository = new TenantSyncRepository(central, clock);
        var organizationId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        central.Organizations.Add(
            new Organization(organizationId, "Organização A", "org-a", Now));
        await central.SaveChangesAsync();

        await using var firstClient = await TestClient.CreateAsync(
            repository,
            organizationId,
            userId,
            clock);
        await using var secondClient = await TestClient.CreateAsync(
            repository,
            organizationId,
            Guid.NewGuid(),
            clock);
        var entityId = Guid.NewGuid();
        var operationId = Guid.NewGuid();
        await firstClient.Store.EnqueueAsync(
            new UpsertClientCommand(operationId, entityId, "Cliente sincronizado", true, 0),
            CancellationToken.None);

        var firstCycle = await firstClient.Service.SynchronizeAsync(CancellationToken.None);
        var secondCycle = await secondClient.Service.SynchronizeAsync(CancellationToken.None);

        Assert.Equal(SyncCycleStatus.Synchronized, firstCycle.Status);
        Assert.Equal(1, secondCycle.CachedClients);
        var synchronized = Assert.Single(
            await secondClient.Store.GetClientsAsync(CancellationToken.None));
        Assert.Equal("Cliente sincronizado", synchronized.DisplayName);

        var duplicate = await repository.ApplyAsync(
            organizationId,
            userId,
            new UpsertClientCommand(operationId, entityId, "Cliente sincronizado", true, 0),
            CancellationToken.None);
        Assert.Equal(SyncCommandStatus.Duplicate, duplicate.Status);
        Assert.Equal(1, await central.AuditEvents.CountAsync());

        await secondClient.Store.EnqueueAsync(
            new UpsertClientCommand(Guid.NewGuid(), entityId, "Edição obsoleta", true, 0),
            CancellationToken.None);
        var conflict = await secondClient.Service.SynchronizeAsync(CancellationToken.None);
        Assert.Equal(SyncCycleStatus.Conflict, conflict.Status);
        Assert.Equal(1, conflict.Conflicts);

        var otherTenant = await repository.PullAsync(Guid.NewGuid(), 0, CancellationToken.None);
        Assert.Empty(otherTenant.Clients);
    }

    [Fact]
    public async Task RevokedDeviceIsRejectedAndAuditIsAppendOnly()
    {
        await using var connection = OpenMemoryConnection();
        await using var context = CreateCentralContext(connection);
        await context.Database.EnsureCreatedAsync();
        var organizationId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var deviceId = Guid.NewGuid();
        context.DeviceSessions.Add(
            new DeviceSession
            {
                Id = deviceId,
                OrganizationId = organizationId,
                UserId = userId,
                DeviceName = "Mac de teste",
                CreatedAtUtc = Now,
                LastSeenAtUtc = Now,
                RevokedAtUtc = Now,
            });
        context.AuditEvents.Add(CreateAuditEvent(organizationId, userId));
        await context.SaveChangesAsync();
        var validator = new DeviceSessionValidator(context);

        Assert.False(await validator.IsActiveAsync(
            organizationId,
            userId,
            deviceId,
            CancellationToken.None));

        var audit = await context.AuditEvents.SingleAsync();
        audit.Action = "tampered";
        await Assert.ThrowsAsync<InvalidOperationException>(() => context.SaveChangesAsync());

        context.ChangeTracker.Clear();
        var authorization = new ProductionDispatchAuthorization
        {
            OperationId = Guid.NewGuid(),
            OrganizationId = organizationId,
            UserId = userId,
            DeviceId = deviceId,
            ProviderKey = "microsoft.graph",
            DispatchFingerprint = new string('a', 64),
            BatchSize = 1,
            AttachmentCount = 1,
            ApplicationVersion = "0.12.0",
            AuthorizationDateUtc = "2026-08-20",
            AuthorizedAtUtc = Now,
        };
        context.ProductionDispatchAuthorizations.Add(authorization);
        await context.SaveChangesAsync();
        authorization.BatchSize = 2;
        await Assert.ThrowsAsync<InvalidOperationException>(() => context.SaveChangesAsync());
    }

    [Fact]
    public async Task LocalSqliteContainsCacheAndQueueButNoTokenColumns()
    {
        await using var connection = OpenMemoryConnection();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        await using var context = new LocalCacheDbContext(options);
        await context.Database.MigrateAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT sql FROM sqlite_master WHERE type = 'table'";
        await using var reader = await command.ExecuteReaderAsync();
        var schema = new List<string>();
        while (await reader.ReadAsync())
        {
            schema.Add(reader.GetString(0));
        }

        var combinedSchema = string.Join('\n', schema);
        Assert.Contains("offline_sync_operations", combinedSchema, StringComparison.Ordinal);
        Assert.DoesNotContain("access_token", combinedSchema, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("refresh_token", combinedSchema, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task PostgreSqlMigrationsApplyWhenIntegrationConnectionIsConfigured()
    {
        var connectionString = Environment.GetEnvironmentVariable("FOLHAS_TEST_POSTGRES");
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            return;
        }

        var builder = new DbContextOptionsBuilder<FolhasDbContext>();
        builder.UseNpgsql(connectionString);
        builder.UseOpenIddict<Guid>();
        await using var context = new FolhasDbContext(builder.Options);
        await context.Database.EnsureDeletedAsync();
        await context.Database.MigrateAsync();

        Assert.True(await context.Database.CanConnectAsync());
        Assert.Contains(
            "20260820225019_InitialCentralPhase2",
            await context.Database.GetAppliedMigrationsAsync());
        Assert.Contains(
            "20260821012511_AddPhase3ClientCatalog",
            await context.Database.GetAppliedMigrationsAsync());
        Assert.Contains(
            "20260822003950_AddPhase12ProductionRollout",
            await context.Database.GetAppliedMigrationsAsync());
        Assert.Contains(
            "20260822150641_AddPartnerOptionalEmail",
            await context.Database.GetAppliedMigrationsAsync());
    }

    private static SqliteConnection OpenMemoryConnection()
    {
        var connection = new SqliteConnection("Data Source=:memory:");
        connection.Open();
        return connection;
    }

    private static FolhasDbContext CreateCentralContext(SqliteConnection connection)
    {
        var builder = new DbContextOptionsBuilder<FolhasDbContext>();
        builder.UseSqlite(connection);
        builder.UseOpenIddict<Guid>();
        return new FolhasDbContext(builder.Options);
    }

    private static AuditEvent CreateAuditEvent(Guid organizationId, Guid userId) =>
        new()
        {
            Id = Guid.NewGuid(),
            OrganizationId = organizationId,
            UserId = userId,
            EntityType = "client",
            EntityId = Guid.NewGuid().ToString("D"),
            Action = "created",
            Category = "registration",
            Severity = "information",
            RedactedDataJson = "{}",
            TimestampUtc = Now,
            CorrelationId = Guid.NewGuid(),
        };

    private sealed class TestClock(DateTimeOffset utcNow) : IClock
    {
        public DateTimeOffset UtcNow { get; } = utcNow;
    }

    private sealed class RepositoryTransport(
        ISyncRepository repository,
        Guid organizationId,
        Guid userId) : ISyncTransport
    {
        public async Task<PushSyncResponse> PushAsync(
            PushSyncRequest request,
            CancellationToken cancellationToken)
        {
            var results = new List<SyncCommandResult>();
            foreach (var command in request.Commands)
            {
                results.Add(await repository.ApplyAsync(
                    organizationId,
                    userId,
                    command,
                    cancellationToken));
            }

            return new PushSyncResponse(results);
        }

        public Task<PullSyncResponse> PullAsync(
            long checkpoint,
            CancellationToken cancellationToken) =>
            repository.PullAsync(organizationId, checkpoint, cancellationToken);
    }

    private sealed class TestClient(
        SqliteConnection connection,
        LocalCacheDbContext context,
        SqliteLocalSyncStore store,
        SyncService service) : IAsyncDisposable
    {
        public SqliteLocalSyncStore Store { get; } = store;

        public SyncService Service { get; } = service;

        public static async Task<TestClient> CreateAsync(
            ISyncRepository repository,
            Guid organizationId,
            Guid userId,
            IClock clock)
        {
            var connection = OpenMemoryConnection();
            var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
                .UseSqlite(connection)
                .Options;
            var context = new LocalCacheDbContext(options);
            var store = new SqliteLocalSyncStore(context, clock);
            await store.InitializeAsync(CancellationToken.None);
            var service = new SyncService(
                store,
                new RepositoryTransport(repository, organizationId, userId),
                clock);
            return new TestClient(connection, context, store, service);
        }

        public async ValueTask DisposeAsync()
        {
            await context.DisposeAsync();
            await connection.DisposeAsync();
        }
    }
}
