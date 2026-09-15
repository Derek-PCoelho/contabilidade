namespace FolhasDaMichelly.Application.Abstractions;

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}
