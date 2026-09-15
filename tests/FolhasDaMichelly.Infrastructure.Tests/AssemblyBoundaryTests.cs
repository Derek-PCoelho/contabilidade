using FolhasDaMichelly.Infrastructure;

namespace FolhasDaMichelly.Infrastructure.Tests;

public sealed class AssemblyBoundaryTests
{
    [Fact]
    public void InfrastructureAssemblyHasExpectedIdentity()
    {
        var assemblyName = typeof(InfrastructureAssemblyMarker).Assembly.GetName().Name;

        Assert.Equal("FolhasDaMichelly.Infrastructure", assemblyName);
    }
}
