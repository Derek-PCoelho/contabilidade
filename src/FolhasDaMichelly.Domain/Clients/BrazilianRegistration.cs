using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Clients;

public enum BrazilianRegistrationValidationError
{
    Required,
    UnsupportedCharacters,
    InvalidLength,
    InvalidCheckDigits,
}

public static class BrazilianRegistration
{
    private static readonly int[] CpfFirstWeights = [10, 9, 8, 7, 6, 5, 4, 3, 2];
    private static readonly int[] CpfSecondWeights = [11, 10, 9, 8, 7, 6, 5, 4, 3, 2];
    private static readonly int[] CnpjFirstWeights = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
    private static readonly int[] CnpjSecondWeights = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];

    public static string NormalizeCpf(string value)
    {
        if (!TryNormalizeCpf(value, out var normalized, out var error))
        {
            throw new DomainValidationException(ToValidationMessage("CPF", 11, error));
        }

        return normalized;
    }

    public static bool TryNormalizeCpf(
        string? value,
        out string normalized,
        out BrazilianRegistrationValidationError? error)
    {
        if (value?.Contains('/', StringComparison.Ordinal) == true)
        {
            normalized = string.Empty;
            error = BrazilianRegistrationValidationError.UnsupportedCharacters;
            return false;
        }

        if (!TryNormalizeDigits(value, 11, out var digits, out error))
        {
            normalized = string.Empty;
            return false;
        }

        if (HasRepeatedDigits(digits) ||
            CalculateDigit(digits.AsSpan(0, 9), CpfFirstWeights) != digits[9] - '0' ||
            CalculateDigit(digits.AsSpan(0, 10), CpfSecondWeights) != digits[10] - '0')
        {
            normalized = string.Empty;
            error = BrazilianRegistrationValidationError.InvalidCheckDigits;
            return false;
        }

        normalized = digits;
        error = null;
        return true;
    }

    public static string NormalizeCnpj(string value)
    {
        var digits = NormalizeDigits(value, 14, "CNPJ");
        if (HasRepeatedDigits(digits) ||
            CalculateDigit(digits.AsSpan(0, 12), CnpjFirstWeights) != digits[12] - '0' ||
            CalculateDigit(digits.AsSpan(0, 13), CnpjSecondWeights) != digits[13] - '0')
        {
            throw new DomainValidationException("CNPJ is mathematically invalid.");
        }

        return digits;
    }

    public static string CnpjRoot(string normalizedCnpj)
    {
        var digits = NormalizeCnpj(normalizedCnpj);
        return digits[..8];
    }

    public static string Mask(string normalizedTaxId) => normalizedTaxId.Length switch
    {
        11 => $"***.***.***-{normalizedTaxId[^2..]}",
        14 => $"**.***.***/****-{normalizedTaxId[^2..]}",
        _ => throw new DomainValidationException("Tax identifier has an unsupported length."),
    };

    public static string FormatCpf(string value)
    {
        var normalized = NormalizeCpf(value);
        return $"{normalized[..3]}.{normalized[3..6]}.{normalized[6..9]}-{normalized[9..]}";
    }

    private static string NormalizeDigits(string value, int expectedLength, string fieldName)
    {
        if (!TryNormalizeDigits(value, expectedLength, out var normalized, out var error))
        {
            throw new DomainValidationException(ToValidationMessage(fieldName, expectedLength, error));
        }

        return normalized;
    }

    private static bool TryNormalizeDigits(
        string? value,
        int expectedLength,
        out string normalized,
        out BrazilianRegistrationValidationError? error)
    {
        normalized = string.Empty;
        if (string.IsNullOrWhiteSpace(value))
        {
            error = BrazilianRegistrationValidationError.Required;
            return false;
        }

        if (value.Any(character =>
                !char.IsAsciiDigit(character) &&
                character is not '.' and not '/' and not '-' &&
                !char.IsWhiteSpace(character)))
        {
            error = BrazilianRegistrationValidationError.UnsupportedCharacters;
            return false;
        }

        var digits = new string(value.Where(char.IsAsciiDigit).ToArray());
        if (digits.Length != expectedLength)
        {
            error = BrazilianRegistrationValidationError.InvalidLength;
            return false;
        }

        normalized = digits;
        error = null;
        return true;
    }

    private static string ToValidationMessage(
        string fieldName,
        int expectedLength,
        BrazilianRegistrationValidationError? error) =>
        error switch
        {
            BrazilianRegistrationValidationError.Required => $"{fieldName} é obrigatório.",
            BrazilianRegistrationValidationError.UnsupportedCharacters =>
                $"{fieldName} aceita somente números, espaços e os sinais de formatação '.', '/' e '-'.",
            BrazilianRegistrationValidationError.InvalidLength =>
                $"{fieldName} deve conter exatamente {expectedLength} números.",
            BrazilianRegistrationValidationError.InvalidCheckDigits =>
                $"{fieldName} possui dígitos verificadores inválidos.",
            _ => $"{fieldName} é inválido.",
        };

    private static bool HasRepeatedDigits(string digits) => digits.All(digit => digit == digits[0]);

    private static int CalculateDigit(ReadOnlySpan<char> digits, int[] weights)
    {
        var sum = 0;
        for (var index = 0; index < digits.Length; index++)
        {
            sum += (digits[index] - '0') * weights[index];
        }

        var remainder = sum % 11;
        return remainder < 2 ? 0 : 11 - remainder;
    }
}
