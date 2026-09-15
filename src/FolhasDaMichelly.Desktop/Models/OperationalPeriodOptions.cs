using System.Globalization;

namespace FolhasDaMichelly.Desktop.Models;

public sealed record OperationalYearOption(int? Year, string Label)
{
    public static OperationalYearOption All { get; } = new(null, "Todos os anos");

    public override string ToString() => Label;
}

public sealed record OperationalMonthOption(int? Month, string Label)
{
    public static IReadOnlyList<OperationalMonthOption> Create()
    {
        var culture = CultureInfo.GetCultureInfo("pt-BR");
        return
        [
            new(null, "Todos os meses"),
            .. Enumerable.Range(1, 12).Select(month => new OperationalMonthOption(
                month,
                culture.DateTimeFormat.GetMonthName(month).ToUpperInvariant())),
            new(0, "Sem mês definido"),
        ];
    }

    public override string ToString() => Label;
}
