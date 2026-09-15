namespace FolhasDaMichelly.Application.Security;

public interface IProtectedBackupService
{
    byte[] Protect(ReadOnlySpan<byte> content, string password);

    byte[] Unprotect(ReadOnlySpan<byte> envelope, string password);
}

public sealed class ProtectedBackupException : Exception
{
    public ProtectedBackupException(string message)
        : base(message)
    {
    }
}
