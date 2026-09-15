using System.Security.Cryptography;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Platform.Storage;
using FolhasDaMichelly.Desktop.ViewModels;

namespace FolhasDaMichelly.Desktop.Views;

public partial class MainWindow : Window
{
    private bool shutdownPrepared;
    private bool openingClientFromList;
    private bool clientSelectionOpenPending;

    public MainWindow()
    {
        InitializeComponent();
        Closing += MainWindow_Closing;
        KeyDown += MainWindow_KeyDown;
    }

    private void MainWindow_KeyDown(object? sender, KeyEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel ||
            !args.KeyModifiers.HasFlag(KeyModifiers.Control))
        {
            return;
        }

        switch (args.Key)
        {
            case Key.D1:
                viewModel.ShowHomeCommand.Execute(null);
                break;
            case Key.D2:
                viewModel.ShowClientsCommand.Execute(null);
                break;
            case Key.D3:
                viewModel.ShowDocumentsCommand.Execute(null);
                break;
            case Key.D4:
                viewModel.ShowDispatchCommand.Execute(null);
                break;
            case Key.D5:
                viewModel.ShowReportsCommand.Execute(null);
                break;
            case Key.D6:
                viewModel.ShowHistoryCommand.Execute(null);
                break;
            case Key.D7:
                viewModel.ShowSettingsCommand.Execute(null);
                break;
            default:
                return;
        }

        args.Handled = true;
    }

    private void ClientSearch_KeyDown(object? sender, KeyEventArgs args)
    {
        if (args.Key != Key.Enter || DataContext is not MainViewModel viewModel)
        {
            return;
        }

        if (viewModel.LoadClientsCommand.CanExecute(null))
        {
            viewModel.LoadClientsCommand.Execute(null);
            args.Handled = true;
        }
    }

    private async void ClientList_SelectionChanged(object? sender, SelectionChangedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel || viewModel.SelectedClient is null)
        {
            return;
        }

        if (openingClientFromList)
        {
            clientSelectionOpenPending = true;
            return;
        }

        if (!viewModel.OpenSelectedClientCommand.CanExecute(null))
        {
            return;
        }

        openingClientFromList = true;
        try
        {
            do
            {
                clientSelectionOpenPending = false;
                await viewModel.OpenSelectedClientCommand.ExecuteAsync(null);
            }
            while (clientSelectionOpenPending &&
                   viewModel.SelectedClient is not null &&
                   viewModel.OpenSelectedClientCommand.CanExecute(null));
        }
        finally
        {
            openingClientFromList = false;
            clientSelectionOpenPending = false;
        }
    }

    private async void MainWindow_Closing(object? sender, WindowClosingEventArgs args)
    {
        if (shutdownPrepared || DataContext is not MainViewModel { KeepEmailSession: false } viewModel)
        {
            return;
        }

        args.Cancel = true;
        try
        {
            await viewModel.PrepareForShutdownAsync(CancellationToken.None);
        }
        finally
        {
            shutdownPrepared = true;
            Close();
        }
    }

    private async void ChoosePdfFiles_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        var files = await StorageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                Title = "Selecione documentos PDF",
                AllowMultiple = true,
                FileTypeFilter =
                [
                    new FilePickerFileType("Documentos PDF")
                    {
                        Patterns = ["*.pdf"],
                        MimeTypes = ["application/pdf"],
                        AppleUniformTypeIdentifiers = ["com.adobe.pdf"],
                    },
                ],
            });
        await ImportAsync(files);
    }

    private async void ChooseInputFolder_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var path = await PickInputFolderAsync();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        await viewModel.SetInputFolderAsync(path, CancellationToken.None);
    }

    private async void ChooseAndImportInputFolder_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var path = await PickInputFolderAsync();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        await viewModel.SetInputFolderAsync(path, CancellationToken.None);
        await viewModel.ImportInputFolderAsync(CancellationToken.None);
    }

    private async Task<string?> PickInputFolderAsync()
    {
        var folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                Title = "Escolha a pasta que contém os documentos",
                AllowMultiple = false,
            });
        return folders.Count == 0 ? null : folders[0].TryGetLocalPath();
    }

    private async void ImportInputFolder_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is MainViewModel viewModel)
        {
            await viewModel.ImportInputFolderAsync(CancellationToken.None);
        }
    }

    private async void ChooseReportFolder_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                Title = "Escolha onde salvar os relatórios",
                AllowMultiple = false,
            });
        var path = folders.Count == 0 ? null : folders[0].TryGetLocalPath();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        await viewModel.SetReportOutputDirectoryAsync(path, CancellationToken.None);
    }

    private async void ChooseDocumentArchiveFolder_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var folders = await StorageProvider.OpenFolderPickerAsync(
            new FolderPickerOpenOptions
            {
                Title = "Escolha a pasta do acervo de documentos",
                AllowMultiple = false,
            });
        var path = folders.Count == 0 ? null : folders[0].TryGetLocalPath();
        if (string.IsNullOrWhiteSpace(path))
        {
            return;
        }

        await viewModel.SetDocumentArchiveDirectoryAsync(path, CancellationToken.None);
    }

    private async void SaveCatalogBackup_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var content = await viewModel.CreateProtectedCatalogBackupAsync(CancellationToken.None);
        if (content is null)
        {
            return;
        }

        try
        {
            var file = await StorageProvider.SaveFilePickerAsync(
                new FilePickerSaveOptions
                {
                    Title = "Salvar cópia de segurança dos cadastros",
                    SuggestedFileName = $"folhas-da-michelly-clientes-{DateTime.Now:yyyy-MM-dd}.fdmbackup",
                    DefaultExtension = "fdmbackup",
                    FileTypeChoices =
                    [
                        new FilePickerFileType("Cópia de segurança do Folhas da Michelly")
                        {
                            Patterns = ["*.fdmbackup"],
                            MimeTypes = ["application/octet-stream"],
                            AppleUniformTypeIdentifiers = ["public.data"],
                        },
                    ],
                });
            if (file is null)
            {
                viewModel.StatusMessage = "A cópia de segurança não foi salva.";
                return;
            }

            await using var stream = await file.OpenWriteAsync();
            stream.SetLength(0);
            await stream.WriteAsync(content);
            await stream.FlushAsync();
            viewModel.StatusMessage = $"Cópia de segurança salva em {file.Name}.";
        }
        finally
        {
            CryptographicOperations.ZeroMemory(content);
            viewModel.ClearCatalogBackupPassword();
        }
    }

    private async void ChooseCatalogBackup_Click(object? sender, Avalonia.Interactivity.RoutedEventArgs args)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var files = await StorageProvider.OpenFilePickerAsync(
            new FilePickerOpenOptions
            {
                Title = "Escolher cópia de segurança dos cadastros",
                AllowMultiple = false,
                FileTypeFilter =
                [
                    new FilePickerFileType("Cópia de segurança do Folhas da Michelly")
                    {
                        Patterns = ["*.fdmbackup"],
                        MimeTypes = ["application/octet-stream"],
                        AppleUniformTypeIdentifiers = ["public.data"],
                    },
                ],
            });
        var file = files.Count == 0 ? null : files[0];
        if (file is null)
        {
            return;
        }

        await using var stream = await file.OpenReadAsync();
        const long maximumBackupSizeBytes = 20 * 1024 * 1024;
        if (stream.CanSeek && stream.Length > maximumBackupSizeBytes)
        {
            viewModel.StatusMessage = "A cópia escolhida é maior que o limite de 20 MB e não foi aberta.";
            return;
        }

        using var memory = new MemoryStream();
        await stream.CopyToAsync(memory);
        var content = memory.ToArray();
        try
        {
            await viewModel.PreviewProtectedCatalogRestoreAsync(content, CancellationToken.None);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(content);
            viewModel.ClearCatalogBackupPassword();
        }
    }

    private void DocumentDropZone_DragOver(object? sender, DragEventArgs args)
    {
        var files = args.DataTransfer.TryGetFiles();
        args.DragEffects = files?.Any(file =>
            string.Equals(Path.GetExtension(file.Name), ".pdf", StringComparison.OrdinalIgnoreCase)) == true
            ? DragDropEffects.Copy
            : DragDropEffects.None;
        args.Handled = true;
    }

    private async void DocumentDropZone_Drop(object? sender, DragEventArgs args)
    {
        args.Handled = true;
        await ImportAsync(args.DataTransfer.TryGetFiles() ?? []);
    }

    private async Task ImportAsync(IEnumerable<IStorageItem> storageItems)
    {
        if (DataContext is not MainViewModel viewModel)
        {
            return;
        }

        var paths = storageItems.Select(item => item.TryGetLocalPath())
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Cast<string>()
            .ToArray();
        await viewModel.ImportDocumentsAsync(paths);
    }
}
