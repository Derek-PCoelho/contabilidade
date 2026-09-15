using System.Globalization;
using Avalonia.Data.Converters;

namespace FolhasDaMichelly.Desktop.Models;

public sealed class LocalDateTimeConverter : IValueConverter
{
    private static readonly CultureInfo BrazilianCulture = CultureInfo.GetCultureInfo("pt-BR");

    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value switch
        {
            DateTimeOffset timestamp => timestamp.ToLocalTime().ToString("dd/MM/yyyy · HH:mm", BrazilianCulture),
            DateTime timestamp => timestamp.ToLocalTime().ToString("dd/MM/yyyy · HH:mm", BrazilianCulture),
            _ => string.Empty,
        };

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}
