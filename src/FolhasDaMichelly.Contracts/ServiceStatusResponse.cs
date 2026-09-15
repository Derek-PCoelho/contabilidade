namespace FolhasDaMichelly.Contracts;

/// <summary>
/// Minimal, non-sensitive service status returned by the Phase 1 server.
/// </summary>
/// <param name="Service">Service display name.</param>
/// <param name="Status">Current service status.</param>
public sealed record ServiceStatusResponse(string Service, string Status);
