using FolhasDaMichelly.Contracts.Clients;

namespace FolhasDaMichelly.Desktop.Models;

public sealed record ClientListRow(
    ClientListItem Source,
    string? PrimaryTaxId,
    bool MaskTaxId)
{
    public Guid Id => Source.Id;

    public PersonTypeModel PersonType => Source.PersonType;

    public string DisplayName => Source.DisplayName;

    public string TaxIdDisplay => !MaskTaxId && !string.IsNullOrWhiteSpace(PrimaryTaxId)
        ? FormatTaxId(PrimaryTaxId, PersonType)
        : Source.PrimaryTaxIdMasked;

    public string? InternalCode => Source.InternalCode;

    public bool IsActive => Source.IsActive;

    public long Version => Source.Version;

    public int EstablishmentCount => Source.EstablishmentCount;

    public int RecipientCount => Source.RecipientCount;

    public DateTimeOffset UpdatedAtUtc => Source.UpdatedAtUtc;

    private static string FormatTaxId(string digits, PersonTypeModel personType)
    {
        var normalized = new string(digits.Where(char.IsAsciiDigit).ToArray());
        return personType switch
        {
            PersonTypeModel.Individual when normalized.Length == 11 =>
                $"{normalized[..3]}.{normalized[3..6]}.{normalized[6..9]}-{normalized[9..]}",
            PersonTypeModel.LegalEntity when normalized.Length == 14 =>
                $"{normalized[..2]}.{normalized[2..5]}.{normalized[5..8]}/{normalized[8..12]}-{normalized[12..]}",
            _ => digits,
        };
    }
}

public sealed record TemplatePlaceholderTargetOption(string Key, string Label)
{
    public override string ToString() => Label;
}
