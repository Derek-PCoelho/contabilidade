package br.com.contadoresassociados.folhas.application.clients;

import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration.ValidationError;

/** Validação amigável do CPF opcional de sócio/representante (mensagens idênticas à versão .NET). */
public final class ClientPartnerInputValidator {

    public record CpfValidation(boolean valid, String normalized, String formatted, String errorMessage) {
        static final CpfValidation EMPTY = new CpfValidation(true, null, null, null);
    }

    private ClientPartnerInputValidator() {
    }

    public static CpfValidation validateOptionalCpf(String value) {
        if (value == null || value.isBlank()) {
            return CpfValidation.EMPTY;
        }
        var r = BrazilianRegistration.tryNormalizeCpf(value);
        if (r.isValid()) {
            return new CpfValidation(true, r.normalized(), BrazilianRegistration.formatCpf(r.normalized()), null);
        }
        var message = switch (r.error()) {
            case UNSUPPORTED_CHARACTERS -> value.contains("@")
                    ? "Você digitou um e-mail no campo CPF. Informe aqui os 11 números do CPF ou deixe o campo vazio; "
                            + "use o campo “E-mail do representante” para o endereço eletrônico."
                    : "O CPF aceita somente 11 números, com ou sem pontos e hífen. Retire letras ou outros símbolos.";
            case INVALID_LENGTH -> "O CPF precisa ter 11 números. Você pode digitá-lo com ou sem pontos e hífen.";
            case INVALID_CHECK_DIGITS -> "Os dígitos verificadores do CPF não conferem. Revise os números digitados.";
            case REQUIRED -> "Revise o CPF do sócio ou representante.";
        };
        return new CpfValidation(false, null, null, message);
    }

    /** Mesmo tratamento para CNPJ (numérico ou alfanumérico) digitado na interface. */
    public static String cnpjProblem(String value) {
        var r = BrazilianRegistration.tryNormalizeCnpj(value);
        if (r.isValid()) {
            return null;
        }
        return switch (r.error()) {
            case REQUIRED -> "Informe o CNPJ.";
            case UNSUPPORTED_CHARACTERS -> "O CNPJ aceita letras (A–Z), números e os sinais '.', '/' e '-'.";
            case INVALID_LENGTH -> "O CNPJ precisa ter 14 posições: 12 letras ou números e 2 dígitos verificadores.";
            case INVALID_CHECK_DIGITS -> r.error() == ValidationError.INVALID_CHECK_DIGITS
                    ? "Os dígitos verificadores do CNPJ não conferem. Revise o que foi digitado." : "";
        };
    }
}
