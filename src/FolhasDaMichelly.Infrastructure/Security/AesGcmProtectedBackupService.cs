using System.Security.Cryptography;
using System.Text.Json;
using FolhasDaMichelly.Application.Security;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class AesGcmProtectedBackupService : IProtectedBackupService
{
    private const int Iterations = 600_000;
    private const int MaximumContentBytes = 10 * 1024 * 1024;
    private const int MaximumEnvelopeBytes = 20 * 1024 * 1024;
    private const int MinimumPasswordLength = 12;
    private static readonly byte[] AssociatedData = "FolhasDaMichelly.Backup.v1"u8.ToArray();
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    public byte[] Protect(ReadOnlySpan<byte> content, string password)
    {
        ValidatePassword(password);
        if (content.IsEmpty || content.Length > MaximumContentBytes)
        {
            throw new ProtectedBackupException("O conteúdo da cópia está vazio ou excede 10 MB.");
        }

        var salt = RandomNumberGenerator.GetBytes(16);
        var nonce = RandomNumberGenerator.GetBytes(12);
        var key = Rfc2898DeriveBytes.Pbkdf2(
            password,
            salt,
            Iterations,
            HashAlgorithmName.SHA256,
            32);
        var cipherText = new byte[content.Length];
        var tag = new byte[16];
        try
        {
            using var cipher = new AesGcm(key, tag.Length);
            cipher.Encrypt(nonce, content, cipherText, tag, AssociatedData);
            return JsonSerializer.SerializeToUtf8Bytes(
                new BackupEnvelope(
                    1,
                    "PBKDF2-HMAC-SHA256",
                    Iterations,
                    "AES-256-GCM",
                    Convert.ToBase64String(salt),
                    Convert.ToBase64String(nonce),
                    Convert.ToBase64String(tag),
                    Convert.ToBase64String(cipherText)),
                SerializerOptions);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            CryptographicOperations.ZeroMemory(cipherText);
        }
    }

    public byte[] Unprotect(ReadOnlySpan<byte> envelope, string password)
    {
        ValidatePassword(password);
        if (envelope.IsEmpty || envelope.Length > MaximumEnvelopeBytes)
        {
            throw InvalidEnvelope();
        }

        byte[]? key = null;
        byte[]? clearText = null;
        try
        {
            var document = JsonSerializer.Deserialize<BackupEnvelope>(envelope, SerializerOptions)
                ?? throw InvalidEnvelope();
            if (document.Version != 1 ||
                document.Iterations != Iterations ||
                document.Kdf != "PBKDF2-HMAC-SHA256" ||
                document.Cipher != "AES-256-GCM")
            {
                throw InvalidEnvelope();
            }

            var salt = Convert.FromBase64String(document.Salt);
            var nonce = Convert.FromBase64String(document.Nonce);
            var tag = Convert.FromBase64String(document.Tag);
            var cipherText = Convert.FromBase64String(document.Content);
            if (salt.Length != 16 || nonce.Length != 12 || tag.Length != 16 ||
                cipherText.Length is 0 or > MaximumContentBytes)
            {
                throw InvalidEnvelope();
            }

            key = Rfc2898DeriveBytes.Pbkdf2(
                password,
                salt,
                document.Iterations,
                HashAlgorithmName.SHA256,
                32);
            clearText = new byte[cipherText.Length];
            using var cipher = new AesGcm(key, tag.Length);
            cipher.Decrypt(nonce, cipherText, tag, clearText, AssociatedData);
            return clearText;
        }
        catch (Exception exception) when (
            exception is JsonException or FormatException or CryptographicException or
            ArgumentException or ProtectedBackupException)
        {
            if (clearText is not null)
            {
                CryptographicOperations.ZeroMemory(clearText);
            }

            throw InvalidEnvelope();
        }
        finally
        {
            if (key is not null)
            {
                CryptographicOperations.ZeroMemory(key);
            }
        }
    }

    private static void ValidatePassword(string password)
    {
        if (string.IsNullOrWhiteSpace(password) ||
            password.Length is < MinimumPasswordLength or > 256)
        {
            throw new ProtectedBackupException(
                "Use uma senha de cópia com pelo menos 12 caracteres.");
        }
    }

    private static ProtectedBackupException InvalidEnvelope() => new(
        "Não foi possível abrir a cópia. Confira a senha e a integridade do arquivo.");

    private sealed record BackupEnvelope(
        int Version,
        string Kdf,
        int Iterations,
        string Cipher,
        string Salt,
        string Nonce,
        string Tag,
        string Content);
}
