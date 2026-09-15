using FolhasDaMichelly.Domain.Clients;

namespace FolhasDaMichelly.Application.Clients;

public sealed record ClientPartnerCpfValidation(
    bool IsValid,
    string? NormalizedCpf,
    string? FormattedCpf,
    string? ErrorMessage)
{
    public static ClientPartnerCpfValidation Empty { get; } = new(true, null, null, null);
}

public static class ClientPartnerInputValidator
{
    public static ClientPartnerCpfValidation ValidateOptionalCpf(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return ClientPartnerCpfValidation.Empty;
        }

        if (BrazilianRegistration.TryNormalizeCpf(value, out var normalized, out var error))
        {
            return new ClientPartnerCpfValidation(
                true,
                normalized,
                BrazilianRegistration.FormatCpf(normalized),
                null);
        }

        var message = error switch
        {
            BrazilianRegistrationValidationError.UnsupportedCharacters when value.Contains('@', StringComparison.Ordinal) =>
                "Você digitou um e-mail no campo CPF. Informe aqui os 11 números do CPF ou deixe o campo vazio; use o campo “E-mail do representante” para o endereço eletrônico.",
            BrazilianRegistrationValidationError.UnsupportedCharacters =>
                "O CPF aceita somente 11 números, com ou sem pontos e hífen. Retire letras ou outros símbolos.",
            BrazilianRegistrationValidationError.InvalidLength =>
                "O CPF precisa ter 11 números. Você pode digitá-lo com ou sem pontos e hífen.",
            BrazilianRegistrationValidationError.InvalidCheckDigits =>
                "Os dígitos verificadores do CPF não conferem. Revise os números digitados.",
            _ => "Revise o CPF do sócio ou representante.",
        };
        return new ClientPartnerCpfValidation(false, null, null, message);
    }
}
