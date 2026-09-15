using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Application.Tests;

public sealed class MessageTemplatePlaceholderCatalogTests
{
    [Theory]
    [InlineData(PersonTypeModel.LegalEntity)]
    [InlineData(PersonTypeModel.Individual)]
    public void StandardPresetIsCompleteAndUsesOnlySupportedFields(PersonTypeModel personType)
    {
        var preset = StandardMessageTemplatePresetCatalog.For(personType);

        Assert.Equal(personType, preset.PersonType);
        Assert.NotEmpty(preset.SubjectTemplate);
        Assert.NotEmpty(preset.BodyTemplate);
        Assert.True(MessageTemplatePlaceholderCatalog.Validate(preset.SubjectTemplate).IsValid);
        var bodyValidation = MessageTemplatePlaceholderCatalog.Validate(preset.BodyTemplate);
        Assert.True(bodyValidation.IsValid);
        Assert.Contains(MessageTemplatePlaceholderCatalog.DueDateListKey, bodyValidation.ReferencedKeys);
    }

    private static readonly string[] ExpectedKeys =
    [
        "cliente.razao_social",
        "cliente.nome_preferencia",
        "cliente.nome_preferencia_ou_razao_social",
        "contato.nome",
        "periodo.rotulo",
        "documentos.lista",
        "documentos.quantidade",
        "vencimentos.lista",
        "operador.nome",
        "escritorio.nome",
    ];

    [Fact]
    public void CatalogPublishesExactlyTheComposerPlaceholdersWithFriendlyUniqueMetadata()
    {
        var definitions = MessageTemplatePlaceholderCatalog.Definitions;

        Assert.Equal(ExpectedKeys.Order(StringComparer.Ordinal), definitions.Select(item => item.Key).Order(StringComparer.Ordinal));
        Assert.Equal(definitions.Count, definitions.Select(item => item.Key).Distinct(StringComparer.Ordinal).Count());
        Assert.Equal(definitions.Count, definitions.Select(item => item.Token).Distinct(StringComparer.Ordinal).Count());
        Assert.All(definitions, definition =>
        {
            Assert.False(string.IsNullOrWhiteSpace(definition.Label));
            Assert.False(string.IsNullOrWhiteSpace(definition.Description));
            Assert.Equal($"{{{{{definition.Key}}}}}", definition.Token);
            Assert.True(MessageTemplatePlaceholderCatalog.IsSupported(definition.Key));
        });
    }

    [Fact]
    public void ValidationReturnsOnlyUnknownTokensAndAcceptsFriendlyWhitespace()
    {
        var result = MessageTemplatePlaceholderCatalog.Validate(
            "Olá {{ contato.nome }}, documentos de {{periodo.rotulo}}. " +
            "Não usar {{cliente.nome_inexistente}} nem {{ CAMPO.INVALIDO }}.");

        Assert.False(result.IsValid);
        Assert.Equal(
            ["CAMPO.INVALIDO", "cliente.nome_inexistente"],
            result.UnknownKeys);
        Assert.Contains(MessageTemplatePlaceholderCatalog.ContactNameKey, result.ReferencedKeys);
        Assert.Contains(MessageTemplatePlaceholderCatalog.PeriodLabelKey, result.ReferencedKeys);
    }

    [Fact]
    public void ReplacementUsesTheSameTokenParserAsValidation()
    {
        const string template = "Olá {{ contato.nome }} — {{periodo.rotulo}} — {{desconhecido}}";

        var rendered = MessageTemplatePlaceholderCatalog.ReplaceTokens(template, key => key switch
        {
            MessageTemplatePlaceholderCatalog.ContactNameKey => "Equipe financeira",
            MessageTemplatePlaceholderCatalog.PeriodLabelKey => "agosto de 2026",
            _ => null,
        });

        Assert.Equal("Olá Equipe financeira — agosto de 2026 — {{desconhecido}}", rendered);
    }

    [Fact]
    public void CatalogDoesNotCreateTokensForUnsupportedKeys()
    {
        Assert.Throws<ArgumentException>(() =>
            MessageTemplatePlaceholderCatalog.ToToken("cliente.campo_inventado"));
    }
}
