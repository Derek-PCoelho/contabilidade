using System.Text;
using FolhasDaMichelly.Infrastructure.Security;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class Phase7NativeSecretStoreTests : IDisposable
{
    private readonly string temporaryDirectory = Path.Combine(
        Path.GetTempPath(),
        "folhas-phase7-vault",
        Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task NativeVaultRoundTripsSyntheticSecretAndRemovesIt()
    {
        if (!OperatingSystem.IsMacOS() && !OperatingSystem.IsWindows())
        {
            return;
        }

        Directory.CreateDirectory(temporaryDirectory);
        var store = new NativeSecretStore(temporaryDirectory);
        var key = $"tests/phase7/{Guid.NewGuid():N}";
        const string secret = "synthetic-token-never-real";
        try
        {
            await store.StoreAsync(key, secret, CancellationToken.None);
            Assert.Equal(secret, await store.RetrieveAsync(key, CancellationToken.None));
            Assert.All(Directory.EnumerateFiles(temporaryDirectory, "*", SearchOption.AllDirectories), path =>
            {
                var bytes = File.ReadAllBytes(path);
                Assert.DoesNotContain(secret, Encoding.UTF8.GetString(bytes), StringComparison.Ordinal);
            });
        }
        finally
        {
            await store.RemoveAsync(key, CancellationToken.None);
        }

        Assert.Null(await store.RetrieveAsync(key, CancellationToken.None));
    }

    public void Dispose()
    {
        if (Directory.Exists(temporaryDirectory))
        {
            Directory.Delete(temporaryDirectory, true);
        }

        GC.SuppressFinalize(this);
    }
}
