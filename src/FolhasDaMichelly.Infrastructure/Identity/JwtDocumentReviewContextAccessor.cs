using System.Text.Json;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Dispatch;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Identity;

namespace FolhasDaMichelly.Infrastructure.Identity;

public sealed class JwtDocumentReviewContextAccessor(ISecretStore secretStore)
    : IDocumentReviewContextAccessor, IDispatchExecutionContextAccessor
{
    private const string AccessTokenKey = "app-session/access-token";
    private const string UnauthenticatedScopeKey = "unauthenticated-connected";
    private const string UnauthenticatedActorId = "unauthenticated-connected-operator";

    public async Task<DocumentReviewContext> GetCurrentAsync(CancellationToken cancellationToken)
    {
        var token = await secretStore.RetrieveAsync(AccessTokenKey, cancellationToken);
        if (string.IsNullOrWhiteSpace(token) || !TryReadContext(token, out var context))
        {
            return new DocumentReviewContext(UnauthenticatedScopeKey, UnauthenticatedActorId);
        }

        return context;
    }

    async Task<DispatchExecutionContext> IDispatchExecutionContextAccessor.GetCurrentAsync(
        CancellationToken cancellationToken)
    {
        var token = await secretStore.RetrieveAsync(AccessTokenKey, cancellationToken);
        if (string.IsNullOrWhiteSpace(token) || !TryReadDispatchContext(token, out var context))
        {
            return new DispatchExecutionContext(
                UnauthenticatedScopeKey,
                UnauthenticatedActorId,
                new HashSet<string>(StringComparer.Ordinal));
        }

        return context;
    }

    private static bool TryReadContext(string token, out DocumentReviewContext context)
    {
        context = null!;
        var segments = token.Split('.');
        if (segments.Length < 2)
        {
            return false;
        }

        try
        {
            using var document = JsonDocument.Parse(DecodeBase64Url(segments[1]));
            var payload = document.RootElement;
            if (!payload.TryGetProperty(AppClaimNames.OrganizationId, out var organizationClaim) ||
                !Guid.TryParse(organizationClaim.GetString(), out var organizationId) ||
                !payload.TryGetProperty("sub", out var subjectClaim) ||
                string.IsNullOrWhiteSpace(subjectClaim.GetString()))
            {
                return false;
            }

            context = new DocumentReviewContext(
                organizationId.ToString("N"),
                subjectClaim.GetString()!);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static bool TryReadDispatchContext(string token, out DispatchExecutionContext context)
    {
        context = null!;
        var segments = token.Split('.');
        if (segments.Length < 2)
        {
            return false;
        }

        try
        {
            using var document = JsonDocument.Parse(DecodeBase64Url(segments[1]));
            var payload = document.RootElement;
            if (!payload.TryGetProperty(AppClaimNames.OrganizationId, out var organizationClaim) ||
                !Guid.TryParse(organizationClaim.GetString(), out var organizationId) ||
                !payload.TryGetProperty("sub", out var subjectClaim) ||
                string.IsNullOrWhiteSpace(subjectClaim.GetString()))
            {
                return false;
            }

            var permissions = new HashSet<string>(StringComparer.Ordinal);
            if (payload.TryGetProperty(AppClaimNames.Permission, out var permissionClaim))
            {
                if (permissionClaim.ValueKind == JsonValueKind.String &&
                    permissionClaim.GetString() is { Length: > 0 } single)
                {
                    permissions.Add(single);
                }
                else if (permissionClaim.ValueKind == JsonValueKind.Array)
                {
                    foreach (var value in permissionClaim.EnumerateArray())
                    {
                        if (value.ValueKind == JsonValueKind.String && value.GetString() is { Length: > 0 } permission)
                        {
                            permissions.Add(permission);
                        }
                    }
                }
            }

            context = new DispatchExecutionContext(
                organizationId.ToString("N"),
                subjectClaim.GetString()!,
                permissions);
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static byte[] DecodeBase64Url(string value)
    {
        var padded = value.Replace('-', '+').Replace('_', '/');
        padded = (padded.Length % 4) switch
        {
            2 => padded + "==",
            3 => padded + "=",
            _ => padded,
        };
        return Convert.FromBase64String(padded);
    }
}
