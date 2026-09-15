using Avalonia;
using FolhasDaMichelly.Infrastructure.Identity;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Velopack;

namespace FolhasDaMichelly.Desktop;

internal static class Program
{
    public static IServiceProvider Services { get; private set; } = null!;

    [STAThread]
    public static void Main(string[] args)
    {
        VelopackApp.Build()
            .SetAutoApplyOnStartup(false)
            .Run();

        using var singleInstance = DesktopSingleInstanceGuard.TryAcquire();
        if (singleInstance is null)
        {
            Console.Error.WriteLine("Folhas da Michelly já está aberto nesta conta de usuário.");
            return;
        }

        var builder = Host.CreateApplicationBuilder(args);
        builder.Services.AddDesktopPhase2(builder.Configuration);
        builder.Services.AddTransient<ViewModels.MainViewModel>();
        using var host = builder.Build();
        host.Start();
        Services = host.Services;
        try
        {
            BuildAvaloniaApp().StartWithClassicDesktopLifetime(args);
        }
        finally
        {
            host.StopAsync().GetAwaiter().GetResult();
        }
    }

    public static AppBuilder BuildAvaloniaApp()
        => AppBuilder.Configure<App>()
            .UsePlatformDetect()
            .WithInterFont()
            .LogToTrace();
}
