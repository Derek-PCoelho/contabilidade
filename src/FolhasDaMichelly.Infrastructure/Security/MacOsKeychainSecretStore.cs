using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Abstractions;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class MacOsKeychainSecretStore : ISecretStore
{
    private const string ServiceName = "com.folhasdamichelly.desktop";
    private const int ItemNotFound = -25300;

    public Task StoreAsync(string key, string value, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ArgumentNullException.ThrowIfNull(value);
        var account = EncodeKey(key);
        var service = Encoding.UTF8.GetBytes(ServiceName);
        var secret = Encoding.UTF8.GetBytes(value);
        var status = NativeMethods.SecKeychainFindGenericPassword(
            IntPtr.Zero,
            checked((uint)service.Length),
            service,
            checked((uint)account.Length),
            account,
            out _,
            out var existingData,
            out var itemRef);
        try
        {
            if (existingData != IntPtr.Zero)
            {
                _ = NativeMethods.SecKeychainItemFreeContent(IntPtr.Zero, existingData);
                existingData = IntPtr.Zero;
            }

            if (status == 0)
            {
                ThrowIfError(NativeMethods.SecKeychainItemModifyAttributesAndData(
                    itemRef,
                    IntPtr.Zero,
                    checked((uint)secret.Length),
                    secret));
            }
            else if (status == ItemNotFound)
            {
                ThrowIfError(NativeMethods.SecKeychainAddGenericPassword(
                    IntPtr.Zero,
                    checked((uint)service.Length),
                    service,
                    checked((uint)account.Length),
                    account,
                    checked((uint)secret.Length),
                    secret,
                    out var newItem));
                Release(newItem);
            }
            else
            {
                ThrowIfError(status);
            }
        }
        finally
        {
            if (existingData != IntPtr.Zero)
            {
                _ = NativeMethods.SecKeychainItemFreeContent(IntPtr.Zero, existingData);
            }

            Release(itemRef);
            CryptographicOperations.ZeroMemory(secret);
        }

        return Task.CompletedTask;
    }

    public Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var account = EncodeKey(key);
        var service = Encoding.UTF8.GetBytes(ServiceName);
        var status = NativeMethods.SecKeychainFindGenericPassword(
            IntPtr.Zero,
            checked((uint)service.Length),
            service,
            checked((uint)account.Length),
            account,
            out var secretLength,
            out var secretData,
            out var itemRef);
        if (status == ItemNotFound)
        {
            return Task.FromResult<string?>(null);
        }

        ThrowIfError(status);
        try
        {
            var bytes = new byte[secretLength];
            Marshal.Copy(secretData, bytes, 0, bytes.Length);
            try
            {
                return Task.FromResult<string?>(Encoding.UTF8.GetString(bytes));
            }
            finally
            {
                CryptographicOperations.ZeroMemory(bytes);
            }
        }
        finally
        {
            _ = NativeMethods.SecKeychainItemFreeContent(IntPtr.Zero, secretData);
            Release(itemRef);
        }
    }

    public Task RemoveAsync(string key, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var account = EncodeKey(key);
        var service = Encoding.UTF8.GetBytes(ServiceName);
        var status = NativeMethods.SecKeychainFindGenericPassword(
            IntPtr.Zero,
            checked((uint)service.Length),
            service,
            checked((uint)account.Length),
            account,
            out _,
            out var secretData,
            out var itemRef);
        if (status == ItemNotFound)
        {
            return Task.CompletedTask;
        }

        ThrowIfError(status);
        try
        {
            ThrowIfError(NativeMethods.SecKeychainItemDelete(itemRef));
        }
        finally
        {
            if (secretData != IntPtr.Zero)
            {
                _ = NativeMethods.SecKeychainItemFreeContent(IntPtr.Zero, secretData);
            }

            Release(itemRef);
        }

        return Task.CompletedTask;
    }

    private static byte[] EncodeKey(string key)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        return Encoding.UTF8.GetBytes(key);
    }

    private static void ThrowIfError(int status)
    {
        if (status != 0)
        {
            throw new InvalidOperationException($"O Keychain recusou a operação (status {status}).");
        }
    }

    private static void Release(IntPtr value)
    {
        if (value != IntPtr.Zero)
        {
            NativeMethods.CFRelease(value);
        }
    }

    private static class NativeMethods
    {
        private const string SecurityFramework = "/System/Library/Frameworks/Security.framework/Security";
        private const string CoreFoundationFramework = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation";

        [DllImport(SecurityFramework)]
        internal static extern int SecKeychainAddGenericPassword(
            IntPtr keychain,
            uint serviceNameLength,
            byte[] serviceName,
            uint accountNameLength,
            byte[] accountName,
            uint passwordLength,
            byte[] passwordData,
            out IntPtr itemRef);

        [DllImport(SecurityFramework)]
        internal static extern int SecKeychainFindGenericPassword(
            IntPtr keychainOrArray,
            uint serviceNameLength,
            byte[] serviceName,
            uint accountNameLength,
            byte[] accountName,
            out uint passwordLength,
            out IntPtr passwordData,
            out IntPtr itemRef);

        [DllImport(SecurityFramework)]
        internal static extern int SecKeychainItemModifyAttributesAndData(
            IntPtr itemRef,
            IntPtr attrList,
            uint length,
            byte[] data);

        [DllImport(SecurityFramework)]
        internal static extern int SecKeychainItemDelete(IntPtr itemRef);

        [DllImport(SecurityFramework)]
        internal static extern int SecKeychainItemFreeContent(IntPtr attrList, IntPtr data);

        [DllImport(CoreFoundationFramework)]
        internal static extern void CFRelease(IntPtr value);
    }
}
