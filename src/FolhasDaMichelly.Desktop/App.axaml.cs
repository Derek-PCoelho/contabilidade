using Avalonia;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Markup.Xaml;
using FolhasDaMichelly.Desktop.ViewModels;
using FolhasDaMichelly.Desktop.Views;
using Microsoft.Extensions.DependencyInjection;

namespace FolhasDaMichelly.Desktop;

public partial class App : Avalonia.Application
{
    public override void Initialize()
    {
        AvaloniaXamlLoader.Load(this);
    }

    public override void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
        {
            var viewModel = Program.Services.GetRequiredService<MainViewModel>();
            desktop.MainWindow = new MainWindow
            {
                DataContext = viewModel,
            };
            _ = viewModel.LoadReviewWorkspaceAsync();
        }

        base.OnFrameworkInitializationCompleted();
    }
}
