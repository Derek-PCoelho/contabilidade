using System.Globalization;
using Avalonia.Data.Converters;

namespace FolhasDaMichelly.Desktop.Models;

public sealed class ShortIdentifierConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is Guid id
            ? $"#{id.ToString("N", CultureInfo.InvariantCulture)[..6].ToUpperInvariant()}"
            : string.Empty;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
