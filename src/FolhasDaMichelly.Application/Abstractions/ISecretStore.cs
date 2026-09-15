namespace FolhasDaMichelly.Application.Abstractions;

public interface ISecretStore
{
    Task StoreAsync(string key, string value, CancellationToken cancellationToken);

    Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken);

    Task RemoveAsync(string key, CancellationToken cancellationToken);
}
