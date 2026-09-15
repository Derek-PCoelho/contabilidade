using System.Net.Mail;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class MicrosoftGraphOptions
{
    public bool Enabled { get; set; }

    public bool EmailSendEnabled { get; set; }

    public string ClientId { get; set; } = string.Empty;

    public string TenantId { get; set; } = "organizations";

    public string RedirectUri { get; set; } = "http://localhost";

    public string ControlledRecipient { get; set; } = string.Empty;

    public Uri ApiBaseAddress { get; set; } = new("https://graph.microsoft.com/v1.0/");

    public int MaximumRetryAttempts { get; set; } = 3;

    public long MaximumAttachmentBytes { get; set; } = 25L * 1024L * 1024L;

    public IReadOnlyList<string> DraftScopes { get; } = ["Mail.ReadWrite"];

    public IReadOnlyList<string> SendScopes { get; } = ["Mail.ReadWrite", "Mail.Send"];

    public bool IsConfigured
    {
        get
        {
            if (!Enabled ||
                !Guid.TryParse(ClientId, out _) ||
                string.IsNullOrWhiteSpace(TenantId) ||
                !Uri.TryCreate(RedirectUri, UriKind.Absolute, out var redirect) ||
                !redirect.IsLoopback ||
                (!string.Equals(redirect.Scheme, Uri.UriSchemeHttp, StringComparison.Ordinal) &&
                 !string.Equals(redirect.Scheme, Uri.UriSchemeHttps, StringComparison.Ordinal)) ||
                !MailAddress.TryCreate(ControlledRecipient, out var controlled))
            {
                return false;
            }

            return string.Equals(
                controlled.Address,
                ControlledRecipient.Trim(),
                StringComparison.OrdinalIgnoreCase);
        }
    }
}
