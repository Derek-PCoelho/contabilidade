namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class LocalStoragePermissions(string dataDirectory)
{
    public void Apply()
    {
        if (OperatingSystem.IsWindows())
        {
            return;
        }

        TrySetDirectory(dataDirectory);
        foreach (var name in new[] { "cache.db", "cache.db-wal", "cache.db-shm" })
        {
            TrySetFile(Path.Combine(dataDirectory, name));
        }
    }

    [System.Runtime.Versioning.UnsupportedOSPlatform("windows")]
    private static void TrySetDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                File.SetUnixFileMode(
                    path,
                    UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // A instalação continua; o modelo de ameaças exige criptografia do volume como controle principal.
        }
    }

    [System.Runtime.Versioning.UnsupportedOSPlatform("windows")]
    private static void TrySetFile(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            }
        }
        catch (Exception exception) when (exception is IOException or UnauthorizedAccessException)
        {
            // A instalação continua; o sistema operacional ainda aplica as permissões do perfil do usuário.
        }
    }
}
