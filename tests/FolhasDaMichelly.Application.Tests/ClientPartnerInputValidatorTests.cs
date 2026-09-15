using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Dispatch;

namespace FolhasDaMichelly.Application.Tests;

public sealed class ClientPartnerInputValidatorTests
{
    [Fact]
    public void EmptyCpfRemainsOptional()
    {
        var result = ClientPartnerInputValidator.ValidateOptionalCpf("  ");

        Assert.True(result.IsValid);
        Assert.Null(result.NormalizedCpf);
        Assert.Null(result.FormattedCpf);
        Assert.Null(result.ErrorMessage);
    }

    [Fact]
    public void EmailInCpfFieldExplainsWhereToMoveTheValue()
    {
        var result = ClientPartnerInputValidator.ValidateOptionalCpf("representante@example.com");

        Assert.False(result.IsValid);
        Assert.Null(result.NormalizedCpf);
        Assert.Null(result.FormattedCpf);
        Assert.Contains("e-mail no campo CPF", result.ErrorMessage, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("E-mail do representante", result.ErrorMessage, StringComparison.Ordinal);
    }

    [Theory]
    [InlineData("123", "11 números")]
    [InlineData("123.456.789-01", "dígitos verificadores")]
    public void InvalidCpfExplainsHowToCorrectIt(string value, string expectedText)
    {
        var result = ClientPartnerInputValidator.ValidateOptionalCpf(value);

        Assert.False(result.IsValid);
        Assert.Contains(expectedText, result.ErrorMessage, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ValidCpfReturnsNormalizedAndFormattedRepresentations()
    {
        var result = ClientPartnerInputValidator.ValidateOptionalCpf("52998224725");

        Assert.True(result.IsValid);
        Assert.Equal("52998224725", result.NormalizedCpf);
        Assert.Equal("529.982.247-25", result.FormattedCpf);
        Assert.Null(result.ErrorMessage);
    }

    [Fact]
    public void DispatchUsesTheOfficialOfficeNameByDefault()
    {
        var options = new DispatchWorkflowOptions();

        Assert.Equal("AL Contadores Associados", DispatchWorkflowOptions.DefaultOfficeName);
        Assert.Equal(DispatchWorkflowOptions.DefaultOfficeName, options.OfficeName);
    }
}
