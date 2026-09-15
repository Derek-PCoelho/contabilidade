using System.Security.Cryptography;
using System.Text.Json;
using FolhasDaMichelly.Application.Preferences;
using FolhasDaMichelly.Application.Updates;
using FolhasDaMichelly.Infrastructure.Updates;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase10UpdateAndPackagingTests
{
    private static readonly JsonSerializerOptions WebSerializerOptions =
        new(JsonSerializerDefaults.Web);

    [Fact]
    public async Task ArtifactIntegrityAcceptsExpectedContentAndRejectsTampering()
    {
        var original = "pacote sintético da fase 10"u8.ToArray();
        var expected = Convert.ToHexString(SHA256.HashData(original));
        await using var valid = new MemoryStream(original);
        Assert.True(await ReleaseArtifactIntegrityVerifier.MatchesSha256Async(
            valid,
            expected,
            CancellationToken.None));

        var tampered = original.ToArray();
        tampered[^1] ^= 0x01;
        await using var altered = new MemoryStream(tampered);
        Assert.False(await ReleaseArtifactIntegrityVerifier.MatchesSha256Async(
            altered,
            expected,
            CancellationToken.None));

        await using var invalidHash = new MemoryStream(original);
        Assert.False(await ReleaseArtifactIntegrityVerifier.MatchesSha256Async(
            invalidHash,
            "hash-invalido",
            CancellationToken.None));
    }

    [Theory]
    [InlineData("")]
    [InlineData("http://updates.example.invalid/folhas")]
    [InlineData("https://usuario:segredo@updates.example.invalid/folhas")]
    [InlineData("https://updates.example.invalid/folhas?token=segredo")]
    public async Task UpdateFeedFailsClosedWhenAddressIsMissingOrUnsafe(string feed)
    {
        using var service = new VelopackAppUpdateService(new VelopackAppUpdateOptions
        {
            FeedBaseUrl = feed,
        });

        var result = await service.CheckAsync(AppUpdateChannel.Stable, CancellationToken.None);

        Assert.Equal(AppUpdateState.Unavailable, result.State);
        Assert.False(result.CanDownload);
        Assert.False(result.CanRestart);
    }

    [Fact]
    public async Task LocalFeedRequiresAnExplicitValidationSetting()
    {
        var localFeed = Path.Combine(Path.GetTempPath(), "folhas-phase10-feed-sintetico");
        using var service = new VelopackAppUpdateService(new VelopackAppUpdateOptions
        {
            FeedBaseUrl = localFeed,
            AllowLocalFeed = false,
        });

        var result = await service.CheckAsync(AppUpdateChannel.Beta, CancellationToken.None);

        Assert.Equal(AppUpdateState.Unavailable, result.State);
        Assert.Contains("não está autorizado", result.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ExistingWorkspacePreferencesDefaultToStableChannel()
    {
        const string phaseNinePayload = """
            {
              "inputFolderPath": null,
              "documentArchiveDirectory": null,
              "reportOutputDirectory": null,
              "includeSubfolders": true,
              "selectedYear": 2026,
              "selectedMonth": 8,
              "keepEmailSession": true,
              "retentionReviewMonths": 0
            }
            """;

        var preferences = JsonSerializer.Deserialize<WorkspacePreferences>(
            phaseNinePayload,
            WebSerializerOptions);

        Assert.NotNull(preferences);
        Assert.Equal("stable", preferences.UpdateChannel);
    }

    [Fact]
    public void ReleaseAutomationSeparatesChannelsAndRejectsUnsignedStableBuilds()
    {
        var root = FindRepositoryRoot();
        var workflow = File.ReadAllText(Path.Combine(root, ".github", "workflows", "release.yml"));
        var macScript = File.ReadAllText(Path.Combine(root, "tools", "release", "pack-macos.sh"));
        var windowsScript = File.ReadAllText(Path.Combine(root, "tools", "release", "pack-windows.ps1"));

        Assert.Contains("workflow_dispatch", workflow, StringComparison.Ordinal);
        Assert.Contains("environment: release-${{ inputs.channel }}", workflow, StringComparison.Ordinal);
        Assert.DoesNotContain("pull_request:", workflow, StringComparison.Ordinal);
        Assert.Contains("$release_rid-$release_maturity", macScript, StringComparison.Ordinal);
        Assert.Contains("$releaseRid-$Maturity", windowsScript, StringComparison.Ordinal);
        Assert.Contains("stable", macScript, StringComparison.Ordinal);
        Assert.Contains("exige assinatura", macScript, StringComparison.Ordinal);
        Assert.Contains("exige assinatura", windowsScript, StringComparison.Ordinal);
        Assert.Contains("UNSIGNED-VALIDATION-ONLY", macScript, StringComparison.Ordinal);
        Assert.Contains("UNSIGNED-VALIDATION-ONLY", windowsScript, StringComparison.Ordinal);
    }

    private static string FindRepositoryRoot()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory);
             directory is not null;
             directory = directory.Parent)
        {
            if (File.Exists(Path.Combine(directory.FullName, "FolhasDaMichelly.slnx")))
            {
                return directory.FullName;
            }
        }

        throw new DirectoryNotFoundException("A raiz do repositório não foi encontrada.");
    }
}
