namespace FolhasDaMichelly.Desktop;

internal sealed class DesktopSingleInstanceGuard : IDisposable
{
    private const string LockFileName = "desktop-instance.lock";
    private readonly FileStream lockStream;

    private DesktopSingleInstanceGuard(FileStream lockStream)
    {
        this.lockStream = lockStream;
    }

    public static DesktopSingleInstanceGuard? TryAcquire(string? lockFilePath = null)
    {
        var path = lockFilePath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "FolhasDaMichelly",
            LockFileName);
        var directory = Path.GetDirectoryName(path) ??
            throw new InvalidOperationException("O caminho do bloqueio local é inválido.");
        Directory.CreateDirectory(directory);
        try
        {
            var stream = new FileStream(
                path,
                FileMode.OpenOrCreate,
                FileAccess.ReadWrite,
                FileShare.None);
            return new DesktopSingleInstanceGuard(stream);
        }
        catch (IOException)
        {
            return null;
        }
    }

    public void Dispose()
    {
        lockStream.Dispose();
        GC.SuppressFinalize(this);
    }
}
