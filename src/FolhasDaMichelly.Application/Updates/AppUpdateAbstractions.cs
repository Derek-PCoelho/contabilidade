using System.Reflection;

namespace FolhasDaMichelly.Application.Updates;

public static class AppVersionInfo
{
    public const string Baseline = "0.12.7";

    public static string Current
    {
        get
        {
            var version = typeof(AppVersionInfo).Assembly.GetName().Version;
            return version is null
                ? Baseline
                : $"{version.Major}.{version.Minor}.{Math.Max(0, version.Build)}";
        }
    }
}

public enum AppUpdateChannel
{
    Stable,
    Beta,
}

public enum AppUpdateState
{
    Unavailable,
    NotInstalled,
    Checking,
    UpToDate,
    Available,
    Downloading,
    ReadyToRestart,
    Failed,
}

public sealed record AppUpdateSnapshot(
    AppUpdateState State,
    string CurrentVersion,
    string? LatestVersion,
    AppUpdateChannel Channel,
    int Progress,
    string Message)
{
    public bool CanDownload => State == AppUpdateState.Available;

    public bool CanRestart => State == AppUpdateState.ReadyToRestart;
}

public interface IAppUpdateService
{
    Task<AppUpdateSnapshot> CheckAsync(
        AppUpdateChannel channel,
        CancellationToken cancellationToken);

    Task<AppUpdateSnapshot> DownloadAsync(
        IProgress<int>? progress,
        CancellationToken cancellationToken);

    void ApplyAndRestart();
}

public sealed class AppUpdateException(string message, Exception? innerException = null)
    : Exception(message, innerException);
