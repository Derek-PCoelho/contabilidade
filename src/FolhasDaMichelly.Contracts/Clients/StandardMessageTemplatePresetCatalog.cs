using FolhasDaMichelly.Contracts.Dispatch;

namespace FolhasDaMichelly.Contracts.Clients;

public sealed record StandardMessageTemplatePreset(
    PersonTypeModel PersonType,
    string Name,
    string SubjectTemplate,
    string BodyTemplate);

public static class StandardMessageTemplatePresetCatalog
{
    private static readonly StandardMessageTemplatePreset legalEntity = Create(
        PersonTypeModel.LegalEntity,
        "Padrão para empresa",
        MessageTemplatePlaceholderCatalog.ToToken(MessageTemplatePlaceholderCatalog.ContactNameKey));

    private static readonly StandardMessageTemplatePreset individual = Create(
        PersonTypeModel.Individual,
        "Padrão para pessoa física",
        MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.ClientPreferredOrLegalNameKey));

    public static IReadOnlyList<StandardMessageTemplatePreset> Presets { get; } =
        Array.AsReadOnly([legalEntity, individual]);

    public static StandardMessageTemplatePreset For(PersonTypeModel personType) => personType switch
    {
        PersonTypeModel.LegalEntity => legalEntity,
        PersonTypeModel.Individual => individual,
        _ => throw new ArgumentOutOfRangeException(nameof(personType)),
    };

    private static StandardMessageTemplatePreset Create(
        PersonTypeModel personType,
        string name,
        string salutationTarget)
    {
        var client = MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.ClientPreferredOrLegalNameKey);
        var period = MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.PeriodLabelKey);
        var documents = MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.DocumentListKey);
        var dueDates = MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.DueDateListKey);
        var office = MessageTemplatePlaceholderCatalog.ToToken(
            MessageTemplatePlaceholderCatalog.OfficeNameKey);
        return new StandardMessageTemplatePreset(
            personType,
            name,
            $"Documentos contábeis - {client} - {period}",
            $"Olá, {salutationTarget},\n\n" +
            $"Encaminhamos em anexo os documentos referentes a {period}:\n{documents}\n\n" +
            $"Vencimentos identificados:\n{dueDates}\n\n" +
            "Pedimos, por gentileza, que confira os anexos. Permanecemos à disposição em caso de dúvidas.\n\n" +
            $"Atenciosamente,\n{office}");
    }
}
