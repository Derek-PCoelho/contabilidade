using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace FolhasDaMichelly.Infrastructure.Dispatch;

public sealed record GmailAuthorizationResponse(
    string? Code,
    string? State,
    string? Error,
    Uri RedirectUri);

public interface IGmailOAuthAuthorizationReceiver
{
    Task<GmailAuthorizationResponse> ReceiveAsync(
        Func<Uri, Uri> createAuthorizationUri,
        string expectedState,
        CancellationToken cancellationToken);
}

public sealed class SystemBrowserGmailOAuthAuthorizationReceiver : IGmailOAuthAuthorizationReceiver
{
    public async Task<GmailAuthorizationResponse> ReceiveAsync(
        Func<Uri, Uri> createAuthorizationUri,
        string expectedState,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(createAuthorizationUri);
        var port = ReserveLoopbackPort();
        var redirectUri = new Uri($"http://127.0.0.1:{port}/oauth2/callback/");
        using var listener = new HttpListener();
        listener.Prefixes.Add(redirectUri.AbsoluteUri);
        listener.Start();

        var authorizationUri = createAuthorizationUri(redirectUri);
        Process.Start(new ProcessStartInfo
        {
            FileName = authorizationUri.AbsoluteUri,
            UseShellExecute = true,
        });

        using var registration = cancellationToken.Register(listener.Close);
        HttpListenerContext context;
        try
        {
            context = await listener.GetContextAsync().WaitAsync(cancellationToken);
        }
        catch (HttpListenerException exception) when (cancellationToken.IsCancellationRequested)
        {
            throw new OperationCanceledException("A conexão Google foi cancelada.", exception, cancellationToken);
        }

        var query = context.Request.QueryString;
        var response = new GmailAuthorizationResponse(
            query["code"],
            query["state"],
            query["error"],
            redirectUri);
        var valid = response.Error is null &&
            !string.IsNullOrWhiteSpace(response.Code) &&
            string.Equals(response.State, expectedState, StringComparison.Ordinal);
        await WriteBrowserResponseAsync(context.Response, valid, cancellationToken);
        return response;
    }

    private static int ReserveLoopbackPort()
    {
        using var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        return ((IPEndPoint)listener.LocalEndpoint).Port;
    }

    private static async Task WriteBrowserResponseAsync(
        HttpListenerResponse response,
        bool success,
        CancellationToken cancellationToken)
    {
        var title = success ? "Conta conectada" : "Conexão não concluída";
        var detail = success
            ? "Você pode fechar esta janela e voltar ao Folhas da Michelly."
            : "Volte ao Folhas da Michelly para tentar novamente.";
        var html = $"<!doctype html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>{title}</title>" +
            $"<body style=\"font-family:system-ui;padding:48px;color:#24221e\"><h1>{title}</h1><p>{detail}</p></body></html>";
        var bytes = Encoding.UTF8.GetBytes(html);
        response.StatusCode = (int)HttpStatusCode.OK;
        response.ContentType = "text/html; charset=utf-8";
        response.ContentLength64 = bytes.Length;
        try
        {
            await response.OutputStream.WriteAsync(bytes, cancellationToken);
        }
        finally
        {
            response.Close();
        }
    }
}
