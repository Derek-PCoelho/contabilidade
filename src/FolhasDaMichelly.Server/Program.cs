using System.Threading.RateLimiting;
using FolhasDaMichelly.Contracts;
using FolhasDaMichelly.Server.Configuration;
using FolhasDaMichelly.Server.Health;
using FolhasDaMichelly.Server.Hubs;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.AspNetCore.RateLimiting;

var builder = WebApplication.CreateBuilder(args);

var isTesting = string.Equals(builder.Environment.EnvironmentName, "Testing", StringComparison.Ordinal);
if (!builder.Environment.IsDevelopment() && !isTesting &&
    string.Equals(builder.Configuration["AllowedHosts"], "*", StringComparison.Ordinal))
{
    throw new InvalidOperationException(
        "AllowedHosts must list the production host names; wildcard hosts are refused.");
}

builder.WebHost.ConfigureKestrel(options =>
{
    options.Limits.MaxRequestBodySize = 12 * 1024 * 1024;
    options.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(15);
    options.Limits.KeepAliveTimeout = TimeSpan.FromMinutes(2);
});
builder.Services.AddProblemDetails(options =>
    options.CustomizeProblemDetails = context =>
    {
        context.ProblemDetails.Instance = null;
        context.ProblemDetails.Extensions.Remove("exception");
        context.ProblemDetails.Extensions["correlationId"] =
            CorrelationIds.Get(context.HttpContext);
    });
builder.Services.AddControllers();
builder.Services.AddSignalR(options => options.EnableDetailedErrors = false);
builder.Services.AddPhase2Services(builder.Configuration, builder.Environment);
builder.Services
    .AddHealthChecks()
    .AddCheck<CentralDatabaseHealthCheck>("central_database", tags: ["ready"]);
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.GlobalLimiter = PartitionedRateLimiter.Create<HttpContext, string>(context =>
        RateLimitPartition.GetFixedWindowLimiter(
            RateLimitPartitions.For(context, "global"),
            _ => RateLimitPartitions.Window(600)));
    options.AddPolicy("authentication", context => RateLimitPartition.GetFixedWindowLimiter(
        RateLimitPartitions.For(context, "authentication", preferUser: false),
        _ => RateLimitPartitions.Window(10)));
    options.AddPolicy("read", context => RateLimitPartition.GetFixedWindowLimiter(
        RateLimitPartitions.For(context, "read"),
        _ => RateLimitPartitions.Window(240)));
    options.AddPolicy("write", context => RateLimitPartition.GetFixedWindowLimiter(
        RateLimitPartitions.For(context, "write"),
        _ => RateLimitPartitions.Window(60)));
    options.AddPolicy("sensitive", context => RateLimitPartition.GetFixedWindowLimiter(
        RateLimitPartitions.For(context, "sensitive"),
        _ => RateLimitPartitions.Window(20)));
    options.OnRejected = async (context, cancellationToken) =>
    {
        context.HttpContext.Response.Headers.RetryAfter = "60";
        await context.HttpContext.Response.WriteAsJsonAsync(
            new
            {
                type = "about:blank",
                title = "Muitas solicitações",
                status = StatusCodes.Status429TooManyRequests,
                detail = "Aguarde um minuto antes de tentar novamente.",
                correlationId = CorrelationIds.Get(context.HttpContext),
            },
            cancellationToken);
    };
});

var app = builder.Build();

app.UseExceptionHandler();
if (!app.Environment.IsDevelopment() && !isTesting)
{
    app.UseHsts();
    app.UseHttpsRedirection();
}

app.Use(
    async (context, next) =>
    {
        var correlationId = CorrelationIds.Normalize(
            context.Request.Headers[CorrelationIds.Header].ToString());
        context.Items[CorrelationIds.ItemKey] = correlationId;
        context.Response.Headers[CorrelationIds.Header] = correlationId;
        context.Response.Headers.XContentTypeOptions = "nosniff";
        context.Response.Headers.XFrameOptions = "DENY";
        context.Response.Headers.Append("Referrer-Policy", "no-referrer");
        context.Response.Headers.Append(
            "Content-Security-Policy",
            "default-src 'none'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
        context.Response.Headers.Append("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (context.Request.Path.StartsWithSegments("/account") ||
            context.Request.Path.StartsWithSegments("/connect"))
        {
            context.Response.Headers.CacheControl = "no-store";
            context.Response.Headers.Pragma = "no-cache";
        }

        await next(context);
    });
app.UseRouting();
app.UseAuthentication();
app.UseRateLimiter();
app.UseAuthorization();

app.MapGet(
    "/",
    () => Results.Ok(new ServiceStatusResponse("Folhas da Michelly API", "Phase12GradualProduction")));
app.MapControllers();
app.MapHub<SyncHub>("/hubs/sync");
app.MapHealthChecks(
    "/health/live",
    new HealthCheckOptions { Predicate = _ => false });
app.MapHealthChecks(
    "/health/ready",
    new HealthCheckOptions { Predicate = registration => registration.Tags.Contains("ready") });

app.Run();

public partial class Program;

internal static class CorrelationIds
{
    public const string Header = "X-Correlation-ID";
    public const string ItemKey = "FolhasDaMichelly.CorrelationId";

    public static string Get(HttpContext context) =>
        context.Items.TryGetValue(ItemKey, out var value) && value is string id
            ? id
            : context.TraceIdentifier;

    public static string Normalize(string? candidate) =>
        !string.IsNullOrWhiteSpace(candidate) &&
        candidate.Length <= 64 &&
        candidate.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or '.')
            ? candidate
            : Guid.NewGuid().ToString("N");
}

internal static class RateLimitPartitions
{
    public static string For(HttpContext context, string policy, bool preferUser = true)
    {
        var subject = preferUser && context.User.Identity?.IsAuthenticated == true
            ? context.User.FindFirst("sub")?.Value ?? context.User.FindFirst(
                System.Security.Claims.ClaimTypes.NameIdentifier)?.Value
            : null;
        var origin = subject ?? context.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        return $"{policy}:{origin}";
    }

    public static FixedWindowRateLimiterOptions Window(int permitLimit) => new()
    {
        PermitLimit = permitLimit,
        Window = TimeSpan.FromMinutes(1),
        QueueLimit = 0,
        AutoReplenishment = true,
    };
}
