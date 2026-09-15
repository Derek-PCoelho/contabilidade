using FolhasDaMichelly.Application.Updates;

namespace FolhasDaMichelly.Desktop.Models;

public sealed record ReleaseChannelOption(AppUpdateChannel Value, string Label, string Help);
