namespace FolhasDaMichelly.Application.Security;

public interface ISensitiveTextRedactor
{
    string Redact(string? value, int maximumLength = 2_000);
}
