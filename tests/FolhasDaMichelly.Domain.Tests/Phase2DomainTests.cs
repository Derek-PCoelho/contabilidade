using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;
using FolhasDaMichelly.Domain.Identity;
using FolhasDaMichelly.Domain.Organizations;

namespace FolhasDaMichelly.Domain.Tests;

public sealed class Phase2DomainTests
{
    private static readonly DateTimeOffset Now = new(2026, 8, 20, 12, 0, 0, TimeSpan.Zero);

    [Fact]
    public void OrganizationNormalizesAndValidatesTenantSlug()
    {
        var organization = new Organization(Guid.NewGuid(), "  Michelly  ", "MATRIZ-01", Now);

        Assert.Equal("Michelly", organization.Name);
        Assert.Equal("matriz-01", organization.Slug);
        Assert.Throws<DomainValidationException>(() =>
            new Organization(Guid.NewGuid(), "Michelly", "inválido/espaço", Now));
    }

    [Fact]
    public void ClientRejectsStaleOptimisticConcurrencyVersion()
    {
        var client = new SynchronizedClient(
            Guid.NewGuid(),
            Guid.NewGuid(),
            "Cliente A",
            Guid.NewGuid(),
            Now);

        var conflict = Assert.Throws<ConcurrencyConflictException>(() =>
            client.Apply("Cliente alterado", true, 0, Guid.NewGuid(), Now.AddMinutes(1)));

        Assert.Equal(1, conflict.CurrentVersion);
        Assert.Equal("Cliente A", client.DisplayName);
    }

    [Fact]
    public void OperatorCannotSendAndAuditorCannotWriteClients()
    {
        Assert.DoesNotContain(
            AppPermissions.EmailSend,
            RolePermissionCatalog.ForRole(AppRoles.Operator));
        Assert.DoesNotContain(
            AppPermissions.ClientsWrite,
            RolePermissionCatalog.ForRole(AppRoles.Auditor));
        Assert.Contains(
            AppPermissions.AuditRead,
            RolePermissionCatalog.ForRole(AppRoles.Auditor));
    }
}
