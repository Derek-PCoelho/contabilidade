using System.Security.Cryptography;

namespace FolhasDaMichelly.Infrastructure.Updates;

public static class ReleaseArtifactIntegrityVerifier
{
    public static async Task<bool> MatchesSha256Async(
        Stream content,
        string expectedSha256,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(content);
        if (!TryDecodeSha256(expectedSha256, out var expected))
        {
            return false;
        }

        var actual = await SHA256.HashDataAsync(content, cancellationToken);
        try
        {
            return CryptographicOperations.FixedTimeEquals(actual, expected);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(actual);
            CryptographicOperations.ZeroMemory(expected);
        }
    }

    private static bool TryDecodeSha256(string value, out byte[] bytes)
    {
        bytes = [];
        if (value.Length != 64)
        {
            return false;
        }

        try
        {
            bytes = Convert.FromHexString(value);
            return bytes.Length == 32;
        }
        catch (FormatException)
        {
            return false;
        }
    }
}
