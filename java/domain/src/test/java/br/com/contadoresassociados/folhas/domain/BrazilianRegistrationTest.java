package br.com.contadoresassociados.folhas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration.ValidationError;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class BrazilianRegistrationTest {

    @ParameterizedTest
    @CsvSource({"529.982.247-25,52998224725", "52998224725,52998224725"})
    void cpfNormalizes(String input, String expected) {
        assertThat(BrazilianRegistration.normalizeCpf(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "representante@example.com,UNSUPPORTED_CHARACTERS",
        "529/982/247-25,UNSUPPORTED_CHARACTERS",
        "123,INVALID_LENGTH",
        "123.456.789-01,INVALID_CHECK_DIGITS",
        "111.111.111-11,INVALID_CHECK_DIGITS"})
    void cpfClassifiesError(String input, ValidationError expected) {
        assertThat(BrazilianRegistration.tryNormalizeCpf(input).error()).isEqualTo(expected);
    }

    @Test
    void cpfLettersAreNeverAccepted() {
        assertThat(BrazilianRegistration.tryNormalizeCpf("5299822472A").error())
                .isEqualTo(ValidationError.UNSUPPORTED_CHARACTERS);
    }

    @Test
    void cpfMessageIsPortugueseWithStableCode() {
        assertThatThrownBy(() -> BrazilianRegistration.normalizeCpf("123.456.789-01"))
                .isInstanceOfSatisfying(DomainValidationException.class, e -> {
                    assertThat(e.code()).isEqualTo("cpf.invalid_check_digits");
                    assertThat(e.getMessage()).isEqualTo("CPF possui dígitos verificadores inválidos.");
                });
    }

    @ParameterizedTest
    @CsvSource({"11.222.333/0001-81,11222333000181", "11222333000181,11222333000181"})
    void numericCnpjStillValid(String input, String expected) {
        assertThat(BrazilianRegistration.normalizeCnpj(input)).isEqualTo(expected);
    }

    /** Pendência 1.1 / 8.2: exemplo oficial do Serpro/RFB para o CNPJ alfanumérico. */
    @ParameterizedTest
    @CsvSource({
        "12.ABC.345/01DE-35,12ABC34501DE35",
        "12abc34501de35,12ABC34501DE35",
        "12 ABC 345 01DE 35,12ABC34501DE35"})
    void alphanumericCnpjIsAccepted(String input, String expected) {
        assertThat(BrazilianRegistration.normalizeCnpj(input)).isEqualTo(expected);
        assertThat(BrazilianRegistration.cnpjRoot(input)).isEqualTo("12ABC345");
        assertThat(BrazilianRegistration.formatCnpj(input)).isEqualTo("12.ABC.345/01DE-35");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12.ABC.345/01DE-36", "12.ABC.345/01DE-3A", "12ABC34501DE3", "11.222.333/0001-82",
            "00000000000000", "52998224725<script>", "12.ÁBC.345/01DE-35"})
    void invalidCnpjRejected(String input) {
        assertThat(BrazilianRegistration.isValidCnpj(input)).isFalse();
        assertThatThrownBy(() -> BrazilianRegistration.normalizeCnpj(input))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void alphanumericCnpjRootNormalizes() {
        assertThat(BrazilianRegistration.normalizeCnpjRoot("12.abc.345")).isEqualTo("12ABC345");
        assertThatThrownBy(() -> BrazilianRegistration.normalizeCnpjRoot("12ABC34"))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void maskKeepsOnlyLastTwo() {
        assertThat(BrazilianRegistration.mask("52998224725")).isEqualTo("***.***.***-25");
        assertThat(BrazilianRegistration.mask("12ABC34501DE35")).isEqualTo("**.***.***/****-35");
    }
}
