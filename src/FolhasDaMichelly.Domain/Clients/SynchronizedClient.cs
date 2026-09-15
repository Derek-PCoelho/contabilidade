using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Clients;

public sealed class SynchronizedClient
{
    private SynchronizedClient()
    {
    }

    public SynchronizedClient(
        Guid id,
        Guid organizationId,
        string displayName,
        Guid changedBy,
        DateTimeOffset now)
    {
        if (id == Guid.Empty || organizationId == Guid.Empty || changedBy == Guid.Empty)
        {
            throw new DomainValidationException(
                "Client, organization, and actor identifiers cannot be empty.");
        }

        Id = id;
        OrganizationId = organizationId;
        DisplayName = NormalizeName(displayName);
        IsActive = true;
        Version = 1;
        CreatedAtUtc = now.ToUniversalTime();
        UpdatedAtUtc = CreatedAtUtc;
        ChangedBy = changedBy;
    }

    public Guid Id { get; private set; }

    public Guid OrganizationId { get; private set; }

    public string DisplayName { get; private set; } = string.Empty;

    public bool IsActive { get; private set; }

    public long Version { get; private set; }

    public Guid ChangedBy { get; private set; }

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public void Apply(
        string displayName,
        bool isActive,
        long expectedVersion,
        Guid changedBy,
        DateTimeOffset now)
    {
        if (expectedVersion != Version)
        {
            throw new ConcurrencyConflictException(Id, expectedVersion, Version);
        }

        if (changedBy == Guid.Empty)
        {
            throw new DomainValidationException("Actor identifier cannot be empty.");
        }

        DisplayName = NormalizeName(displayName);
        IsActive = isActive;
        ChangedBy = changedBy;
        UpdatedAtUtc = now.ToUniversalTime();
        Version++;
    }

    private static string NormalizeName(string value)
    {
        var normalized = value.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > 200)
        {
            throw new DomainValidationException(
                "Client display name must contain between 1 and 200 characters.");
        }

        return normalized;
    }
}

public sealed class ConcurrencyConflictException(
    Guid entityId,
    long expectedVersion,
    long currentVersion)
    : Exception($"Client {entityId} expected version {expectedVersion}, but current version is {currentVersion}.")
{
    public Guid EntityId { get; } = entityId;

    public long ExpectedVersion { get; } = expectedVersion;

    public long CurrentVersion { get; } = currentVersion;
}
