using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Organizations;

public sealed class Organization
{
    private Organization()
    {
    }

    public Organization(Guid id, string name, string slug, DateTimeOffset now)
    {
        if (id == Guid.Empty)
        {
            throw new DomainValidationException("Organization id cannot be empty.");
        }

        Id = id;
        Name = Required(name, 160, "Organization name");
        Slug = NormalizeSlug(slug);
        IsActive = true;
        CreatedAtUtc = now.ToUniversalTime();
        UpdatedAtUtc = CreatedAtUtc;
    }

    public Guid Id { get; private set; }

    public string Name { get; private set; } = string.Empty;

    public string Slug { get; private set; } = string.Empty;

    public bool IsActive { get; private set; }

    public DateTimeOffset CreatedAtUtc { get; private set; }

    public DateTimeOffset UpdatedAtUtc { get; private set; }

    private static string NormalizeSlug(string value)
    {
        var slug = Required(value, 80, "Organization slug").ToLowerInvariant();
        if (slug.Any(character => !char.IsAsciiLetterOrDigit(character) && character != '-'))
        {
            throw new DomainValidationException(
                "Organization slug can contain only ASCII letters, digits, and hyphens.");
        }

        return slug;
    }

    private static string Required(string value, int maximumLength, string fieldName)
    {
        var normalized = value.Trim();
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Length > maximumLength)
        {
            throw new DomainValidationException(
                $"{fieldName} must contain between 1 and {maximumLength} characters.");
        }

        return normalized;
    }
}
