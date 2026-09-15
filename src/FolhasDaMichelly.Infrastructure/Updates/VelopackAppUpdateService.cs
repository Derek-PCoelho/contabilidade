using System.ComponentModel;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text.Json;
using FolhasDaMichelly.Application.Updates;
using Velopack;
using Velopack.Exceptions;

namespace FolhasDaMichelly.Infrastructure.Updates;

public sealed class VelopackAppUpdateOptions
{
    public string FeedBaseUrl { get; set; } = string.Empty;

    public bool AllowLocalFeed { get; set; }

    public int MaximumDeltasBeforeFallback { get; set; } = 3;
}

public sealed class VelopackAppUpdateService(VelopackAppUpdateOptions options) : IAppUpdateService, IDisposable
{
    private readonly SemaphoreSlim operationLock = new(1, 1);
    private UpdateManager? updateManager;
    private UpdateInfo? pendingUpdate;
    private bool updateDownloaded;
    private AppUpdateChannel activeChannel = AppUpdateChannel.Stable;

    public async Task<AppUpdateSnapshot> CheckAsync(
        AppUpdateChannel channel,
        CancellationToken cancellationToken)
    {
        await operationLock.WaitAsync(cancellationToken);
        try
        {
            var currentVersion = GetCurrentVersion();
            if (!TryResolveFeed(options, out var feed, out var feedError))
            {
                return Snapshot(
                    AppUpdateState.Unavailable,
                    currentVersion,
                    channel,
                    feedError);
            }

            var channelName = ResolveChannelName(channel);
            if (channelName is null)
            {
                return Snapshot(
                    AppUpdateState.Unavailable,
                    currentVersion,
                    channel,
                    "Esta arquitetura ainda não possui um canal de atualização autorizado.");
            }

            updateManager = new UpdateManager(
                feed,
                new UpdateOptions
                {
                    ExplicitChannel = channelName,
                    AllowVersionDowngrade = false,
                    MaximumDeltasBeforeFallback = Math.Clamp(
                        options.MaximumDeltasBeforeFallback,
                        0,
                        10),
                });
            activeChannel = channel;
            pendingUpdate = null;
            updateDownloaded = false;
            if (!updateManager.IsInstalled)
            {
                return Snapshot(
                    AppUpdateState.NotInstalled,
                    currentVersion,
                    channel,
                    "A verificação funciona depois que o aplicativo é instalado por um pacote oficial.");
            }

            var available = await updateManager.CheckForUpdatesAsync();
            if (available is null)
            {
                return Snapshot(
                    AppUpdateState.UpToDate,
                    updateManager.CurrentVersion?.ToNormalizedString() ?? currentVersion,
                    channel,
                    "Esta instalação já está atualizada neste canal.");
            }

            pendingUpdate = available;
            return new AppUpdateSnapshot(
                AppUpdateState.Available,
                updateManager.CurrentVersion?.ToNormalizedString() ?? currentVersion,
                available.TargetFullRelease.Version.ToNormalizedString(),
                channel,
                0,
                "Há uma atualização verificada disponível. Baixe quando puder reiniciar o aplicativo.");
        }
        catch (NotInstalledException)
        {
            return Snapshot(
                AppUpdateState.NotInstalled,
                GetCurrentVersion(),
                channel,
                "A verificação funciona depois que o aplicativo é instalado por um pacote oficial.");
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            pendingUpdate = null;
            updateDownloaded = false;
            return Snapshot(
                AppUpdateState.Failed,
                GetCurrentVersion(),
                channel,
                "A consulta ao canal demorou mais que o permitido. Tente novamente depois.");
        }
        catch (Exception exception) when (exception is
            HttpRequestException or
            IOException or
            InvalidOperationException or
            JsonException or
            UnauthorizedAccessException or
            PlatformNotSupportedException or
            Win32Exception)
        {
            pendingUpdate = null;
            updateDownloaded = false;
            return Snapshot(
                AppUpdateState.Failed,
                GetCurrentVersion(),
                channel,
                "Não foi possível consultar atualizações agora. O trabalho local continua disponível.");
        }
        finally
        {
            operationLock.Release();
        }
    }

    public async Task<AppUpdateSnapshot> DownloadAsync(
        IProgress<int>? progress,
        CancellationToken cancellationToken)
    {
        await operationLock.WaitAsync(cancellationToken);
        try
        {
            if (updateManager is null || pendingUpdate is null)
            {
                throw new AppUpdateException("Verifique se há atualização antes de iniciar o download.");
            }

            await updateManager.DownloadUpdatesAsync(
                pendingUpdate,
                value => progress?.Report(value),
                cancellationToken);
            updateDownloaded = true;
            return new AppUpdateSnapshot(
                AppUpdateState.ReadyToRestart,
                updateManager.CurrentVersion?.ToNormalizedString() ?? GetCurrentVersion(),
                pendingUpdate.TargetFullRelease.Version.ToNormalizedString(),
                activeChannel,
                100,
                "Atualização baixada e conferida. Reinicie pelo botão para aplicar com segurança.");
        }
        catch (ChecksumFailedException exception)
        {
            pendingUpdate = null;
            updateDownloaded = false;
            throw new AppUpdateException(
                "O pacote recebido foi alterado ou está corrompido e foi rejeitado.",
                exception);
        }
        catch (AcquireLockFailedException exception)
        {
            throw new AppUpdateException(
                "Outra atualização já está em andamento nesta instalação.",
                exception);
        }
        catch (AppUpdateException)
        {
            throw;
        }
        catch (OperationCanceledException exception) when (!cancellationToken.IsCancellationRequested)
        {
            updateDownloaded = false;
            throw new AppUpdateException(
                "O download demorou mais que o permitido. Nenhuma versão foi aplicada.",
                exception);
        }
        catch (Exception exception) when (exception is
            HttpRequestException or
            IOException or
            InvalidOperationException or
            JsonException or
            UnauthorizedAccessException or
            PlatformNotSupportedException or
            Win32Exception)
        {
            updateDownloaded = false;
            throw new AppUpdateException(
                "Não foi possível baixar a atualização. Nenhuma versão foi aplicada.",
                exception);
        }
        finally
        {
            operationLock.Release();
        }
    }

    public void ApplyAndRestart()
    {
        if (!updateDownloaded || updateManager is null || pendingUpdate is null)
        {
            throw new AppUpdateException("Baixe e confira a atualização antes de reiniciar.");
        }

        try
        {
            updateManager.ApplyUpdatesAndRestart(pendingUpdate.TargetFullRelease);
        }
        catch (Exception exception) when (exception is
            IOException or
            InvalidOperationException or
            UnauthorizedAccessException or
            PlatformNotSupportedException or
            Win32Exception or
            NotInstalledException)
        {
            throw new AppUpdateException(
                "Não foi possível iniciar a atualização. O aplicativo atual permanece disponível.",
                exception);
        }
    }

    public void Dispose() => operationLock.Dispose();

    internal static bool TryResolveFeed(
        VelopackAppUpdateOptions options,
        out string feed,
        out string error)
    {
        feed = options.FeedBaseUrl.Trim();
        if (feed.Length == 0)
        {
            error = "O canal oficial ainda não foi publicado para esta instalação.";
            return false;
        }

        if (Uri.TryCreate(feed, UriKind.Absolute, out var uri) &&
            string.Equals(uri.Scheme, Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase) &&
            string.IsNullOrEmpty(uri.UserInfo) &&
            string.IsNullOrEmpty(uri.Query) &&
            string.IsNullOrEmpty(uri.Fragment))
        {
            error = string.Empty;
            return true;
        }

        if (options.AllowLocalFeed && Path.IsPathFullyQualified(feed))
        {
            error = string.Empty;
            return true;
        }

        error = "O endereço do canal de atualização não é HTTPS ou não está autorizado.";
        return false;
    }

    internal static string? ResolveChannelName(AppUpdateChannel channel)
    {
        var suffix = channel == AppUpdateChannel.Beta ? "beta" : "stable";
        if (OperatingSystem.IsWindows() && RuntimeInformation.ProcessArchitecture == Architecture.X64)
        {
            return $"win-x64-{suffix}";
        }

        if (OperatingSystem.IsMacOS() && RuntimeInformation.ProcessArchitecture == Architecture.Arm64)
        {
            return $"osx-arm64-{suffix}";
        }

        return null;
    }

    private static string GetCurrentVersion()
    {
        var version = Assembly.GetEntryAssembly()?.GetName().Version;
        return version is null
            ? AppVersionInfo.Current
            : $"{version.Major}.{version.Minor}.{Math.Max(0, version.Build)}";
    }

    private static AppUpdateSnapshot Snapshot(
        AppUpdateState state,
        string currentVersion,
        AppUpdateChannel channel,
        string message) => new(state, currentVersion, null, channel, 0, message);

}
