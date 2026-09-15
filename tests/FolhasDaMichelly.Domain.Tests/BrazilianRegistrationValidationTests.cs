using FolhasDaMichelly.Domain.Clients;
using FolhasDaMichelly.Domain.Common;

namespace FolhasDaMichelly.Domain.Tests;

public sealed class BrazilianRegistrationValidationTests
{
    [Theory]
    [InlineData("529.982.247-25", "52998224725")]
    [InlineData("52998224725", "52998224725")]
    public void TryNormalizeCpfPreservesAValidNormalizedValue(string value, string expected)
    {
        var isValid = BrazilianRegistration.TryNormalizeCpf(value, out var normalized, out var error);

        Assert.True(isValid);
        Assert.Equal(expected, normalized);
        Assert.Null(error);
        Assert.Equal("529.982.247-25", BrazilianRegistration.FormatCpf(normalized));
    }

    [Theory]
    [InlineData("representante@example.com", BrazilianRegistrationValidationError.UnsupportedCharacters)]
    [InlineData("529/982/247-25", BrazilianRegistrationValidationError.UnsupportedCharacters)]
    [InlineData("123", BrazilianRegistrationValidationError.InvalidLength)]
    [InlineData("123.456.789-01", BrazilianRegistrationValidationError.InvalidCheckDigits)]
    public void TryNormalizeCpfClassifiesTheCorrectionNeeded(
        string value,
        BrazilianRegistrationValidationError expectedError)
    {
        var isValid = BrazilianRegistration.TryNormalizeCpf(value, out var normalized, out var error);

        Assert.False(isValid);
        Assert.Empty(normalized);
        Assert.Equal(expectedError, error);
    }

    [Fact]
    public void NormalizeCpfExplainsInvalidCheckDigitsInPortuguese()
    {
        var error = Assert.Throws<DomainValidationException>(() =>
            BrazilianRegistration.NormalizeCpf("123.456.789-01"));

        Assert.Equal("CPF possui dígitos verificadores inválidos.", error.Message);
    }
}
