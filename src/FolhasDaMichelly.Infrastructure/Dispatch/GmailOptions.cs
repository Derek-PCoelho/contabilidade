using System.Net.Mail;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed class GmailOptions
{
    public const string ComposeScope = "https://www.googleapis.com/auth/gmail.compose";

    public bool Enabled { get; set; }

    public bool EmailSendEnabled { get; set; }

    public string ClientId { get; set; } = string.Empty;

    public string ControlledRecipient { get; set; } = string.Empty;

    public Uri AuthorizationEndpoint { get; set; } =
        new("https://accounts.google.com/o/oauth2/v2/auth");

    public Uri TokenEndpoint { get; set; } =
        new("https://oauth2.googleapis.com/token");

    public Uri RevocationEndpoint { get; set; } =
        new("https://oauth2.googleapis.com/revoke");

    public Uri ApiBaseAddress { get; set; } =
        new("https://gmail.googleapis.com/gmail/v1/");

    public int MaximumRetryAttempts { get; set; } = 3;

    public long MaximumAttachmentBytes { get; set; } = 25L * 1024L * 1024L;

    public IReadOnlyList<string> Scopes { get; } = [ComposeScope];

    public bool IsConfigured
    {
        get
        {
            if (!Enabled ||
                string.IsNullOrWhiteSpace(ClientId) ||
                !ClientId.Trim().EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal) ||
                !MailAddress.TryCreate(ControlledRecipient, out var controlled) ||
                !IsSecureGoogleEndpoint(AuthorizationEndpoint) ||
                !IsSecureGoogleEndpoint(TokenEndpoint) ||
                !IsSecureGoogleEndpoint(RevocationEndpoint) ||
                !IsSecureGoogleEndpoint(ApiBaseAddress))
            {
                return false;
            }

            return string.Equals(
                controlled.Address,
                ControlledRecipient.Trim(),
                StringComparison.OrdinalIgnoreCase);
        }
    }

    private static bool IsSecureGoogleEndpoint(Uri uri) =>
        uri.IsAbsoluteUri && uri.Scheme == Uri.UriSchemeHttps;
}
