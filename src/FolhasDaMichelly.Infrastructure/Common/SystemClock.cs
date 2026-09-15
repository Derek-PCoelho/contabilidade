using FolhasDaMichelly.Application.Abstractions;

namespace FolhasDaMichelly.Infrastructure.Common;

public sealed class SystemClock : IClock
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}
