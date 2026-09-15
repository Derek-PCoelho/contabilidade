using System.Collections.Frozen;
using System.Text.RegularExpressions;

namespace FolhasDaMichelly.Contracts.Dispatch;

public sealed record MessageTemplatePlaceholderDefinition(
    string Key,
    string Label,
    string Description)
{
    public string Token => MessageTemplatePlaceholderCatalog.ToToken(Key);
}

public sealed record MessageTemplatePlaceholderValidationResult(
    IReadOnlyList<string> ReferencedKeys,
    IReadOnlyList<string> UnknownKeys)
{
    public bool IsValid => UnknownKeys.Count == 0;
}

public static partial class MessageTemplatePlaceholderCatalog
{
    public const string ClientLegalNameKey = "cliente.razao_social";
    public const string ClientPreferredNameKey = "cliente.nome_preferencia";
    public const string ClientPreferredOrLegalNameKey = "cliente.nome_preferencia_ou_razao_social";
    public const string ContactNameKey = "contato.nome";
    public const string PeriodLabelKey = "periodo.rotulo";
    public const string DocumentListKey = "documentos.lista";
    public const string DocumentCountKey = "documentos.quantidade";
    public const string DueDateListKey = "vencimentos.lista";
    public const string OperatorNameKey = "operador.nome";
    public const string OfficeNameKey = "escritorio.nome";

    private static readonly IReadOnlyList<MessageTemplatePlaceholderDefinition> definitions =
        Array.AsReadOnly<MessageTemplatePlaceholderDefinition>(
        [
            new(
                ClientPreferredOrLegalNameKey,
                "Nome do cliente",
                "Usa o nome de preferência e, quando ele não existe, usa a razão social."),
            new(
                ClientLegalNameKey,
                "Razão social",
                "Insere a razão social ou o nome completo cadastrado."),
            new(
                ClientPreferredNameKey,
                "Nome de preferência",
                "Insere o nome de preferência; se estiver vazio, usa a razão social."),
            new(
                ContactNameKey,
                "Nome do contato",
                "Insere o nome do contato principal que receberá a mensagem."),
            new(
                PeriodLabelKey,
                "Competência",
                "Insere o mês e o ano do grupo de documentos."),
            new(
                DocumentListKey,
                "Lista de documentos",
                "Insere uma linha para cada documento anexado."),
            new(
                DocumentCountKey,
                "Quantidade de documentos",
                "Insere o total de documentos anexados."),
            new(
                DueDateListKey,
                "Vencimentos",
                "Insere os vencimentos identificados ou informa que não foram encontrados."),
            new(
                OfficeNameKey,
                "Nome do escritório",
                "Insere o nome configurado para o escritório."),
            new(
                OperatorNameKey,
                "Nome do operador",
                "Insere a identificação do operador responsável pela preparação."),
        ]);

    private static readonly FrozenSet<string> supportedKeys = definitions
        .Select(definition => definition.Key)
        .ToFrozenSet(StringComparer.Ordinal);

    public static IReadOnlyList<MessageTemplatePlaceholderDefinition> Definitions => definitions;

    public static IReadOnlySet<string> SupportedKeys => supportedKeys;

    public static bool IsSupported(string key) =>
        !string.IsNullOrWhiteSpace(key) && supportedKeys.Contains(key.Trim());

    public static string ToToken(string key)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        var normalized = key.Trim();
        if (!supportedKeys.Contains(normalized))
        {
            throw new ArgumentException("A chave informada não pertence ao catálogo de campos permitidos.", nameof(key));
        }

        return $"{{{{{normalized}}}}}";
    }

    public static MessageTemplatePlaceholderValidationResult Validate(string? template)
    {
        if (string.IsNullOrEmpty(template))
        {
            return new MessageTemplatePlaceholderValidationResult([], []);
        }

        var referenced = PlaceholderRegex().Matches(template)
            .Select(match => NormalizeKey(match.Groups[1].Value))
            .Distinct(StringComparer.Ordinal)
            .Order(StringComparer.Ordinal)
            .ToArray();
        var unknown = referenced
            .Where(key => !supportedKeys.Contains(key))
            .ToArray();
        return new MessageTemplatePlaceholderValidationResult(referenced, unknown);
    }

    public static string ReplaceTokens(string template, Func<string, string?> valueResolver)
    {
        ArgumentNullException.ThrowIfNull(template);
        ArgumentNullException.ThrowIfNull(valueResolver);
        return PlaceholderRegex().Replace(template, match =>
        {
            var replacement = valueResolver(NormalizeKey(match.Groups[1].Value));
            return replacement ?? match.Value;
        });
    }

    private static string NormalizeKey(string value) => value.Trim();

    [GeneratedRegex(@"\{\{\s*([^{}\r\n]+?)\s*\}\}", RegexOptions.CultureInvariant)]
    private static partial Regex PlaceholderRegex();
}
