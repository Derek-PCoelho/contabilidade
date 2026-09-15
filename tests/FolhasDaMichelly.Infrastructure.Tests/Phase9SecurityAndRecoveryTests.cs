using System.Text;
using FolhasDaMichelly.Application.Incidents;
using FolhasDaMichelly.Application.Security;
using FolhasDaMichelly.Infrastructure.Incidents;
using FolhasDaMichelly.Infrastructure.Persistence.Local;
using FolhasDaMichelly.Infrastructure.Security;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase9SecurityAndRecoveryTests
{
    [Fact]
    public void ProtectedBackupRoundTripsWithoutPlaintextAndRejectsWrongPasswordOrTampering()
    {
        var service = new AesGcmProtectedBackupService();
        var clearText = Encoding.UTF8.GetBytes(
            "{\"client\":\"Cliente sintético\",\"email\":\"cliente@example.invalid\"}");
        var protectedContent = service.Protect(clearText, "senha-sintetica-forte-2026");

        Assert.DoesNotContain("Cliente sintético", Encoding.UTF8.GetString(protectedContent), StringComparison.Ordinal);
        Assert.Equal(clearText, service.Unprotect(protectedContent, "senha-sintetica-forte-2026"));
        var wrongPassword = Assert.Throws<ProtectedBackupException>(() =>
            service.Unprotect(protectedContent, "outra-senha-sintetica-2026"));
        var tampered = protectedContent.ToArray();
        tampered[^20] ^= 0x01;
        var altered = Assert.Throws<ProtectedBackupException>(() =>
            service.Unprotect(tampered, "senha-sintetica-forte-2026"));
        Assert.Equal(wrongPassword.Message, altered.Message);
    }

    [Fact]
    public void RedactorMasksEmailTaxIdentifiersAndSecretsBeforePersistence()
    {
        var redactor = new SensitiveTextRedactor();
        var value = redactor.Redact(
            "cliente@example.invalid CPF 123.456.789-01 CNPJ 12.345.678/0001-90 bearer segredo-super-secreto");

        Assert.DoesNotContain("cliente@example.invalid", value, StringComparison.Ordinal);
        Assert.DoesNotContain("123.456.789-01", value, StringComparison.Ordinal);
        Assert.DoesNotContain("12.345.678/0001-90", value, StringComparison.Ordinal);
        Assert.DoesNotContain("segredo-super-secreto", value, StringComparison.Ordinal);
    }

    [Fact]
    public async Task IncidentAndAppendOnlyAuditRecoverAfterSqliteRestartAndStayScopeIsolated()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<LocalCacheDbContext>()
            .UseSqlite(connection)
            .Options;
        var incidentId = Guid.NewGuid();
        var now = new DateTimeOffset(2026, 8, 21, 12, 0, 0, TimeSpan.Zero);
        var incident = new IncidentRecord(
            incidentId,
            "scope-a",
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            Guid.NewGuid(),
            IncidentCategory.Other,
            IncidentSeverity.Medium,
            IncidentStatus.Open,
            "Relato exclusivamente sintético.",
            null,
            "operator-a",
            now,
            "operator-a",
            now,
            null,
            1);
        var audit = new IncidentAuditEvent(
            Guid.NewGuid(),
            "scope-a",
            incidentId,
            "operator-a",
            now,
            "incident_opened",
            null,
            IncidentStatus.Open,
            "Relato exclusivamente sintético.");
        await using (var first = new LocalCacheDbContext(options))
        {
            await first.Database.EnsureCreatedAsync();
            await new SqliteIncidentStore(first).SaveAsync(
                new IncidentWorkspace("scope-a", [incident], [audit]),
                CancellationToken.None);
        }

        await using (var restarted = new LocalCacheDbContext(options))
        {
            var store = new SqliteIncidentStore(restarted);
            var recovered = await store.LoadAsync("scope-a", CancellationToken.None);
            Assert.Equal(incident, Assert.Single(recovered.Incidents));
            Assert.Equal(audit, Assert.Single(recovered.AuditEvents));
            var updated = incident with
            {
                Status = IncidentStatus.Investigating,
                Version = 2,
                UpdatedAtUtc = now.AddMinutes(5),
            };
            var secondAudit = audit with
            {
                Id = Guid.NewGuid(),
                PreviousStatus = IncidentStatus.Open,
                CurrentStatus = IncidentStatus.Investigating,
                TimestampUtc = now.AddMinutes(5),
                Action = "incident_status_changed",
            };
            await store.SaveAsync(
                new IncidentWorkspace("scope-a", [updated], [audit, secondAudit]),
                CancellationToken.None);
            var saved = await store.LoadAsync("scope-a", CancellationToken.None);
            Assert.Equal(2, saved.AuditEvents.Count);
            Assert.Equal(2, Assert.Single(saved.Incidents).Version);
            Assert.Empty((await store.LoadAsync("scope-b", CancellationToken.None)).Incidents);
        }
    }
}
