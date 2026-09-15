using System.Net;

namespace FolhasDaMichelly.Server.Tests;

public sealed class Phase9ApiHardeningTests : IClassFixture<Phase2WebApplicationFactory>
{
    private readonly Phase2WebApplicationFactory factory;

    public Phase9ApiHardeningTests(Phase2WebApplicationFactory factory)
    {
        this.factory = factory;
    }

    [Fact]
    public async Task SecurityHeadersAndNormalizedCorrelationIdAreReturnedWithoutRequestDetails()
    {
        using var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Correlation-ID", "valor inválido com espaços");

        using var response = await client.GetAsync("/health/live?cpf=12345678901");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("nosniff", response.Headers.GetValues("X-Content-Type-Options").Single());
        Assert.Equal("DENY", response.Headers.GetValues("X-Frame-Options").Single());
        var correlationId = response.Headers.GetValues("X-Correlation-ID").Single();
        Assert.Equal(32, correlationId.Length);
        Assert.DoesNotContain("12345678901", correlationId, StringComparison.Ordinal);
        Assert.True(response.Headers.Contains("Content-Security-Policy"));
    }

    [Fact]
    public async Task AuthenticationRateLimitRejectsEleventhRequestWithRetryAfterAndGenericBody()
    {
        using var client = factory.CreateClient();
        HttpResponseMessage? response = null;
        for (var index = 0; index < 11; index++)
        {
            response?.Dispose();
            response = await client.GetAsync("/account/login");
        }

        using (response)
        {
            Assert.NotNull(response);
            Assert.Equal(HttpStatusCode.TooManyRequests, response.StatusCode);
            Assert.Equal("60", response.Headers.GetValues("Retry-After").Single());
            var body = await response.Content.ReadAsStringAsync();
            Assert.Contains("correlationId", body, StringComparison.Ordinal);
            Assert.DoesNotContain("account/login", body, StringComparison.Ordinal);
        }
    }
}
