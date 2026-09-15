using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Abstractions;

namespace FolhasDaMichelly.Infrastructure.Security;

public sealed class WindowsDpapiSecretStore(string directory) : ISecretStore
{
    private const uint CryptProtectUiForbidden = 0x1;

    public async Task StoreAsync(string key, string value, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(value);
        var path = GetPath(key);
        Directory.CreateDirectory(directory);
        var clear = Encoding.UTF8.GetBytes(value);
        try
        {
            var protectedBytes = Protect(clear);
            var temporary = $"{path}.{Guid.NewGuid():N}.tmp";
            try
            {
                await File.WriteAllBytesAsync(temporary, protectedBytes, cancellationToken);
                File.Move(temporary, path, true);
            }
            finally
            {
                if (File.Exists(temporary))
                {
                    File.Delete(temporary);
                }
            }
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }
    }

    public async Task<string?> RetrieveAsync(string key, CancellationToken cancellationToken)
    {
        var path = GetPath(key);
        if (!File.Exists(path))
        {
            return null;
        }

        var protectedBytes = await File.ReadAllBytesAsync(path, cancellationToken);
        var clear = Unprotect(protectedBytes);
        try
        {
            return Encoding.UTF8.GetString(clear);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(clear);
        }
    }

    public Task RemoveAsync(string key, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var path = GetPath(key);
        if (File.Exists(path))
        {
            File.Delete(path);
        }

        return Task.CompletedTask;
    }

    private string GetPath(string key)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        var name = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(key))).ToLowerInvariant();
        return Path.Combine(directory, $"{name}.dpapi");
    }

    private static byte[] Protect(byte[] clear) => Transform(clear, protect: true);

    private static byte[] Unprotect(byte[] cipher) => Transform(cipher, protect: false);

    private static byte[] Transform(byte[] input, bool protect)
    {
        var inputPointer = Marshal.AllocHGlobal(input.Length);
        Marshal.Copy(input, 0, inputPointer, input.Length);
        var inputBlob = new DataBlob(input.Length, inputPointer);
        try
        {
            var succeeded = protect
                ? NativeMethods.CryptProtectData(
                    ref inputBlob,
                    null,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    CryptProtectUiForbidden,
                    out var outputBlob)
                : NativeMethods.CryptUnprotectData(
                    ref inputBlob,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    IntPtr.Zero,
                    CryptProtectUiForbidden,
                    out outputBlob);
            if (!succeeded)
            {
                throw new InvalidOperationException(
                    $"O DPAPI recusou a operação (erro {Marshal.GetLastPInvokeError()}).");
            }

            try
            {
                var output = new byte[outputBlob.Length];
                Marshal.Copy(outputBlob.Data, output, 0, output.Length);
                return output;
            }
            finally
            {
                NativeMethods.LocalFree(outputBlob.Data);
            }
        }
        finally
        {
            Marshal.Copy(new byte[input.Length], 0, inputPointer, input.Length);
            Marshal.FreeHGlobal(inputPointer);
        }
    }

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct DataBlob(int length, IntPtr data)
    {
        public readonly int Length = length;
        public readonly IntPtr Data = data;
    }

    private static class NativeMethods
    {
        [DllImport("Crypt32.dll", EntryPoint = "CryptProtectData", SetLastError = true, CharSet = CharSet.Unicode)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CryptProtectData(
            ref DataBlob dataIn,
            string? description,
            IntPtr optionalEntropy,
            IntPtr reserved,
            IntPtr prompt,
            uint flags,
            out DataBlob dataOut);

        [DllImport("Crypt32.dll", EntryPoint = "CryptUnprotectData", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        internal static extern bool CryptUnprotectData(
            ref DataBlob dataIn,
            IntPtr description,
            IntPtr optionalEntropy,
            IntPtr reserved,
            IntPtr prompt,
            uint flags,
            out DataBlob dataOut);

        [DllImport("Kernel32.dll")]
        internal static extern IntPtr LocalFree(IntPtr memory);
    }
}
