using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Organizations;
using FolhasDaMichelly.Infrastructure.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using OpenIddict.EntityFrameworkCore.Models;

namespace FolhasDaMichelly.Infrastructure.Persistence.Central;

public sealed class FolhasDbContext(
    DbContextOptions<FolhasDbContext> options)
    : IdentityDbContext<ApplicationUser, ApplicationRole, Guid>(options)
{
    public DbSet<Organization> Organizations => Set<Organization>();

    public DbSet<SynchronizedClient> SynchronizedClients => Set<SynchronizedClient>();

    public DbSet<Client> Clients => Set<Client>();

    public DbSet<ClientIdentifier> ClientIdentifiers => Set<ClientIdentifier>();

    public DbSet<Establishment> Establishments => Set<Establishment>();

    public DbSet<Recipient> Recipients => Set<Recipient>();

    public DbSet<ClientPartner> ClientPartners => Set<ClientPartner>();

    public DbSet<MessageTemplate> MessageTemplates => Set<MessageTemplate>();

    public DbSet<DeviceSession> DeviceSessions => Set<DeviceSession>();

    public DbSet<SyncOperation> SyncOperations => Set<SyncOperation>();

    public DbSet<SyncChange> SyncChanges => Set<SyncChange>();

    public DbSet<AuditEvent> AuditEvents => Set<AuditEvent>();

    public DbSet<ProductionDispatchAuthorization> ProductionDispatchAuthorizations =>
        Set<ProductionDispatchAuthorization>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        ArgumentNullException.ThrowIfNull(builder);
        base.OnModelCreating(builder);
        builder.UseOpenIddict<Guid>();

        builder.Entity<Organization>(entity =>
        {
            entity.ToTable("organizations");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.Name).HasMaxLength(160).IsRequired();
            entity.Property(item => item.Slug).HasMaxLength(80).IsRequired();
            entity.HasIndex(item => item.Slug).IsUnique();
        });

        builder.Entity<SynchronizedClient>(entity =>
        {
            entity.ToTable("sync_clients");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.DisplayName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.Version).IsConcurrencyToken();
            entity.HasIndex(item => new { item.OrganizationId, item.DisplayName });
            entity.HasIndex(item => new { item.OrganizationId, item.UpdatedAtUtc });
        });

        builder.Entity<Client>(entity =>
        {
            entity.ToTable("clients");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.PersonType).HasConversion<string>().HasMaxLength(32).IsRequired();
            entity.Property(item => item.LegalNameOrFullName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.PreferredName).HasMaxLength(160);
            entity.Property(item => item.InternalCode).HasMaxLength(80);
            entity.Property(item => item.PrimaryTaxIdNormalized).HasMaxLength(14).IsRequired();
            entity.Property(item => item.Notes).HasMaxLength(2000);
            entity.Property(item => item.Version).IsConcurrencyToken();
            entity.HasIndex(item => new { item.OrganizationId, item.PrimaryTaxIdNormalized }).IsUnique();
            entity.HasIndex(item => new { item.OrganizationId, item.InternalCode }).IsUnique();
            entity.HasIndex(item => new { item.OrganizationId, item.IsActive, item.LegalNameOrFullName });
            entity.HasMany(item => item.Identifiers)
                .WithOne()
                .HasForeignKey(item => item.ClientId)
                .OnDelete(DeleteBehavior.Restrict);
            entity.HasMany(item => item.Establishments)
                .WithOne()
                .HasForeignKey(item => item.ClientId)
                .OnDelete(DeleteBehavior.Restrict);
            entity.HasMany(item => item.Recipients)
                .WithOne()
                .HasForeignKey(item => item.ClientId)
                .OnDelete(DeleteBehavior.Restrict);
            entity.HasMany(item => item.Partners)
                .WithOne()
                .HasForeignKey(item => item.ClientId)
                .OnDelete(DeleteBehavior.Restrict);
            entity.Ignore("isInitializing");
        });

        builder.Entity<ClientIdentifier>(entity =>
        {
            entity.ToTable("client_identifiers");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.Type).HasConversion<string>().HasMaxLength(32).IsRequired();
            entity.Property(item => item.ValueNormalized).HasMaxLength(200).IsRequired();
            entity.Property(item => item.SemanticRole).HasConversion<string>().HasMaxLength(40).IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.Type, item.ValueNormalized }).IsUnique();
            entity.HasIndex(item => new { item.ClientId, item.IsActive, item.Priority });
        });

        builder.Entity<Establishment>(entity =>
        {
            entity.ToTable("client_establishments");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.CnpjNormalized).HasMaxLength(14).IsRequired();
            entity.Property(item => item.CnpjRoot).HasMaxLength(8).IsRequired();
            entity.Property(item => item.LegalName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.DisplayName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.InternalCode).HasMaxLength(80);
            entity.HasIndex(item => new { item.OrganizationId, item.CnpjNormalized }).IsUnique();
            entity.HasIndex(item => new { item.ClientId, item.IsActive });
        });

        builder.Entity<Recipient>(entity =>
        {
            entity.ToTable("client_recipients");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.DisplayName).HasMaxLength(160).IsRequired();
            entity.Property(item => item.EmailNormalized).HasMaxLength(254).IsRequired();
            entity.Property(item => item.DeliveryRole).HasConversion<string>().HasMaxLength(32).IsRequired();
            entity.HasIndex(item => new
            {
                item.ClientId,
                item.EstablishmentId,
                item.EmailNormalized,
                item.DeliveryRole,
                item.DocumentTypeId,
            }).IsUnique();
            entity.HasIndex(item => new { item.ClientId, item.IsActive });
        });

        builder.Entity<ClientPartner>(entity =>
        {
            entity.ToTable("client_partners");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.FullName).HasMaxLength(200).IsRequired();
            entity.Property(item => item.CpfNormalized).HasMaxLength(11);
            entity.Property(item => item.EmailNormalized).HasMaxLength(254);
            entity.Property(item => item.Role).HasConversion<string>().HasMaxLength(40).IsRequired();
            entity.HasIndex(item => new { item.ClientId, item.IsActive, item.FullName });
            entity.HasIndex(item => new { item.OrganizationId, item.CpfNormalized });
        });

        builder.Entity<MessageTemplate>(entity =>
        {
            entity.ToTable("message_templates");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.Name).HasMaxLength(160).IsRequired();
            entity.Property(item => item.SubjectTemplate).HasMaxLength(500).IsRequired();
            entity.Property(item => item.BodyTemplate).HasMaxLength(20_000).IsRequired();
            entity.Property(item => item.SignatureMode).HasConversion<string>().HasMaxLength(32).IsRequired();
            entity.Property(item => item.Version).IsConcurrencyToken();
            entity.HasIndex(item => new { item.OrganizationId, item.ClientId, item.Name }).IsUnique();
            entity.HasIndex(item => new { item.OrganizationId, item.IsActive, item.ClientId });
        });

        builder.Entity<ApplicationUser>(entity =>
        {
            entity.Property(item => item.DisplayName).HasMaxLength(160).IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.NormalizedEmail });
        });

        builder.Entity<DeviceSession>(entity =>
        {
            entity.ToTable("device_sessions");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.DeviceName).HasMaxLength(160).IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.UserId, item.RevokedAtUtc });
        });

        builder.Entity<SyncOperation>(entity =>
        {
            entity.ToTable("sync_operations");
            entity.HasKey(item => new { item.OrganizationId, item.OperationId });
            entity.Property(item => item.Status).HasMaxLength(32).IsRequired();
        });

        builder.Entity<SyncChange>(entity =>
        {
            entity.ToTable("sync_changes");
            entity.HasKey(item => item.Checkpoint);
            entity.Property(item => item.Checkpoint).ValueGeneratedOnAdd();
            entity.Property(item => item.EntityType).HasMaxLength(80).IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.Checkpoint });
        });

        builder.Entity<AuditEvent>(entity =>
        {
            entity.ToTable("audit_events");
            entity.HasKey(item => item.Id);
            entity.Property(item => item.EntityType).HasMaxLength(80).IsRequired();
            entity.Property(item => item.EntityId).HasMaxLength(80).IsRequired();
            entity.Property(item => item.Action).HasMaxLength(80).IsRequired();
            entity.Property(item => item.Category).HasMaxLength(80).IsRequired();
            entity.Property(item => item.Severity).HasMaxLength(32).IsRequired();
            entity.Property(item => item.RedactedDataJson).HasColumnType("jsonb").IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.TimestampUtc });
        });

        builder.Entity<ProductionDispatchAuthorization>(entity =>
        {
            entity.ToTable("production_dispatch_authorizations");
            entity.HasKey(item => new { item.OrganizationId, item.OperationId });
            entity.Property(item => item.ProviderKey).HasMaxLength(40).IsRequired();
            entity.Property(item => item.DispatchFingerprint).HasMaxLength(64).IsRequired();
            entity.Property(item => item.ApplicationVersion).HasMaxLength(32).IsRequired();
            entity.Property(item => item.AuthorizationDateUtc).HasMaxLength(10).IsRequired();
            entity.HasIndex(item => new { item.OrganizationId, item.AuthorizationDateUtc });
            entity.HasIndex(item => new { item.OrganizationId, item.UserId, item.AuthorizationDateUtc });
        });

        RenameIdentityTables(builder);
        RenameOpenIddictTables(builder);
    }

    public override int SaveChanges(bool acceptAllChangesOnSuccess)
    {
        GuardAppendOnlyAudit();
        return base.SaveChanges(acceptAllChangesOnSuccess);
    }

    public override Task<int> SaveChangesAsync(
        bool acceptAllChangesOnSuccess,
        CancellationToken cancellationToken = default)
    {
        GuardAppendOnlyAudit();
        return base.SaveChangesAsync(acceptAllChangesOnSuccess, cancellationToken);
    }

    private static void RenameIdentityTables(ModelBuilder builder)
    {
        builder.Entity<ApplicationUser>().ToTable("users");
        builder.Entity<ApplicationRole>().ToTable("roles");
        builder.Entity<Microsoft.AspNetCore.Identity.IdentityUserRole<Guid>>().ToTable("user_roles");
        builder.Entity<Microsoft.AspNetCore.Identity.IdentityUserClaim<Guid>>().ToTable("user_claims");
        builder.Entity<Microsoft.AspNetCore.Identity.IdentityUserLogin<Guid>>().ToTable("user_logins");
        builder.Entity<Microsoft.AspNetCore.Identity.IdentityRoleClaim<Guid>>().ToTable("role_claims");
        builder.Entity<Microsoft.AspNetCore.Identity.IdentityUserToken<Guid>>().ToTable("user_tokens");
    }

    private static void RenameOpenIddictTables(ModelBuilder builder)
    {
        builder.Entity<OpenIddictEntityFrameworkCoreApplication<Guid>>().ToTable("oidc_applications");
        builder.Entity<OpenIddictEntityFrameworkCoreAuthorization<Guid>>().ToTable("oidc_authorizations");
        builder.Entity<OpenIddictEntityFrameworkCoreScope<Guid>>().ToTable("oidc_scopes");
        builder.Entity<OpenIddictEntityFrameworkCoreToken<Guid>>().ToTable("oidc_tokens");
    }

    private void GuardAppendOnlyAudit()
    {
        if (ChangeTracker.Entries<AuditEvent>().Any(
                entry => entry.State is EntityState.Modified or EntityState.Deleted))
        {
            throw new InvalidOperationException("Audit events are append-only.");
        }

        if (ChangeTracker.Entries<ProductionDispatchAuthorization>().Any(
                entry => entry.State is EntityState.Modified or EntityState.Deleted))
        {
            throw new InvalidOperationException("Production dispatch authorizations are append-only.");
        }
    }
}
