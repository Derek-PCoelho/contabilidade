using System.Text.RegularExpressions;
using FolhasDaMichelly.Application.Security;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed partial class SensitiveTextRedactor : ISensitiveTextRedactor
{
    public string Redact(string? value, int maximumLength = 2_000)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return string.Empty;
        }

        var result = BearerToken().Replace(value, "$1[SEGREDO REDIGIDO]");
        result = Jwt().Replace(result, "[TOKEN REDIGIDO]");
        result = Email().Replace(result, match =>
        {
            var parts = match.Value.Split('@', 2);
            return $"{parts[0][0]}***@{parts[1]}";
        });
        result = TaxId().Replace(result, "[DOCUMENTO FISCAL REDIGIDO]");
        return result.Length <= maximumLength ? result : result[..maximumLength];
    }

    [GeneratedRegex("(?i)(bearer|access[_ -]?token|refresh[_ -]?token|password|senha|authorization)\\s*[:=]?\\s*[^\\s,;]+")]
    private static partial Regex BearerToken();

    [GeneratedRegex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]{10,})?\\b")]
    private static partial Regex Jwt();

    [GeneratedRegex("\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOptions.IgnoreCase)]
    private static partial Regex Email();

    [GeneratedRegex("(?<!\\d)(?:\\d[.\\/-]?){10,14}(?!\\d)")]
    private static partial Regex TaxId();
}
