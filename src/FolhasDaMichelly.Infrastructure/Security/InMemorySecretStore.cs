using System.Collections.Concurrent;
using FolhasDaMichelly.Application.Abstractions;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class InMemorySecretStore : ISecretStore
{
    private readonly ConcurrentDictionary<string, string> values = new(StringComparer.Ordinal);

    public Task StoreAsync(
        string key,
        string value,
        CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        values[ValidateKey(key)] = value;
        return Task.CompletedTask;
    }

    public Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        values.TryGetValue(ValidateKey(key), out var value);
        return Task.FromResult(value);
    }

    public Task RemoveAsync(string key, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        values.TryRemove(ValidateKey(key), out _);
        return Task.CompletedTask;
    }

    private static string ValidateKey(string key)
    {
        if (string.IsNullOrWhiteSpace(key))
        {
            throw new ArgumentException("Secret key cannot be empty.", nameof(key));
        }

        return key;
    }
}
