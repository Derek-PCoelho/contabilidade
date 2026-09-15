using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Clients;

public enum SignatureMode
{
    None,
    User,
    Organization,
}

public sealed record MessageTemplateDraft(
    Guid? ClientId,
    Guid? DocumentTypeId,
    string Name,
    string SubjectTemplate,
    string BodyTemplate,
    SignatureMode SignatureMode,
    bool IsDefault,
    bool IsActive);

public sealed class MessageTemplate
{
    private MessageTemplate()
    {
    }

    public MessageTemplate(
        Guid id,
        Guid organizationId,
        MessageTemplateDraft draft,
        Guid actorId,
        DateTimeOffset now)
    {
        if (id == Guid.Empty || organizationId == Guid.Empty || actorId == Guid.Empty)
        {
            throw new DomainValidationException(
                "Template, organization, and actor identifiers cannot be empty.");
        }

        Id = id;
        OrganizationId = organizationId;
        Version = 0;
        Apply(draft, 0, actorId, now);
    }

    public Guid Id { get; private set; }
    public Guid OrganizationId { get; private set; }
    public Guid? ClientId { get; private set; }
    public Guid? DocumentTypeId { get; private set; }
    public string Name { get; private set; } = string.Empty;
    public string SubjectTemplate { get; private set; } = string.Empty;
    public string BodyTemplate { get; private set; } = string.Empty;
    public SignatureMode SignatureMode { get; private set; }
    public bool IsDefault { get; private set; }
    public bool IsActive { get; private set; }
    public long Version { get; private set; }
    public Guid UpdatedBy { get; private set; }
    public DateTimeOffset UpdatedAtUtc { get; private set; }

    public void Apply(
        MessageTemplateDraft draft,
        long expectedVersion,
        Guid actorId,
        DateTimeOffset now)
    {
        ArgumentNullException.ThrowIfNull(draft);
        if (expectedVersion != Version)
        {
            throw new ConcurrencyConflictException(Id, expectedVersion, Version);
        }

        if (actorId == Guid.Empty)
        {
            throw new DomainValidationException("Actor identifier cannot be empty.");
        }

        ClientId = draft.ClientId is { } clientId && clientId != Guid.Empty ? clientId : null;
        DocumentTypeId = draft.DocumentTypeId is { } typeId && typeId != Guid.Empty ? typeId : null;
        Name = Required(draft.Name, 160, "Template name");
        SubjectTemplate = Required(draft.SubjectTemplate, 500, "Subject template");
        BodyTemplate = Required(draft.BodyTemplate, 20_000, "Body template");
        SignatureMode = draft.SignatureMode;
        IsDefault = draft.IsDefault;
        IsActive = draft.IsActive;
        UpdatedBy = actorId;
        UpdatedAtUtc = now.ToUniversalTime();
        Version++;
    }

    private static string Required(string value, int maximumLength, string fieldName)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length is 0 || normalized.Length > maximumLength)
        {
            throw new DomainValidationException(
                $"{fieldName} must contain between 1 and {maximumLength} characters.");
        }

        return normalized;
    }
}
