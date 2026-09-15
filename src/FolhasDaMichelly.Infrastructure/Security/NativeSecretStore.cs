using FolhasDaMichelly.Application.Abstractions;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class NativeSecretStore : ISecretStore
{
    private readonly ISecretStore implementation;

    public NativeSecretStore(string dataDirectory)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(dataDirectory);
        implementation = OperatingSystem.IsMacOS()
            ? new MacOsKeychainSecretStore()
            : OperatingSystem.IsWindows()
                ? new WindowsDpapiSecretStore(Path.Combine(dataDirectory, "SecureStore"))
                : throw new PlatformNotSupportedException(
                    "O cofre persistente é suportado somente no macOS e Windows.");
    }

    public Task StoreAsync(string key, string value, CancellationToken cancellationToken) =>
        implementation.StoreAsync(key, value, cancellationToken);

    public Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken) =>
        implementation.RetrieveAsync(key, cancellationToken);

    public Task RemoveAsync(string key, CancellationToken cancellationToken) =>
        implementation.RemoveAsync(key, cancellationToken);
}
