using System.Globalization;
using System.Net.Mail;
using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Clients;

public static class EmailAddress
{
    public static string Normalize(string value)
    {
        var normalized = value?.Trim() ?? string.Empty;
        if (normalized.Length is 0 or > 254 ||
            normalized.Any(character => char.IsWhiteSpace(character) || char.IsControl(character)) ||
            !MailAddress.TryCreate(normalized, out var address) ||
            !string.Equals(address.Address, normalized, StringComparison.OrdinalIgnoreCase))
        {
            throw Invalid();
        }

        var separator = normalized.LastIndexOf('@');
        if (separator is <= 0 || separator == normalized.Length - 1)
        {
            throw Invalid();
        }

        var localPart = normalized[..separator];
        var domain = normalized[(separator + 1)..];
        if (localPart.Length > 64 ||
            localPart.StartsWith('.') ||
            localPart.EndsWith('.') ||
            localPart.Contains("..", StringComparison.Ordinal) ||
            localPart.Any(character => !IsAllowedLocalPartCharacter(character)))
        {
            throw Invalid();
        }

        string asciiDomain;
        try
        {
            asciiDomain = new IdnMapping().GetAscii(domain).ToLowerInvariant();
        }
        catch (ArgumentException)
        {
            throw Invalid();
        }

        var labels = asciiDomain.Split('.');
        if (asciiDomain.Length > 253 || labels.Length < 2 ||
            labels.Any(label => label.Length is 0 or > 63 ||
                !char.IsAsciiLetterOrDigit(label[0]) ||
                !char.IsAsciiLetterOrDigit(label[^1]) ||
                label.Any(character => !char.IsAsciiLetterOrDigit(character) && character != '-')) ||
            labels[^1].Length < 2)
        {
            throw Invalid();
        }

        return $"{localPart.ToLowerInvariant()}@{asciiDomain}";
    }

    public static bool TryNormalize(string? value, out string normalized)
    {
        try
        {
            normalized = Normalize(value ?? string.Empty);
            return true;
        }
        catch (DomainValidationException)
        {
            normalized = string.Empty;
            return false;
        }
    }

    private static bool IsAllowedLocalPartCharacter(char character) =>
        char.IsAsciiLetterOrDigit(character) ||
        character is '.' or '!' or '#' or '$' or '%' or '&' or '\'' or '*' or '+' or '-' or '/' or
            '=' or '?' or '^' or '_' or '`' or '{' or '|' or '}' or '~';

    private static DomainValidationException Invalid() =>
        new("Informe um e-mail válido, com endereço e domínio completos (ex.: financeiro@empresa.com.br).");
}
