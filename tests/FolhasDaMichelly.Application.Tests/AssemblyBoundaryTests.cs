using FolhasDaMichelly.Application;

namespace FolhasDaMichelly.Application.Tests;

public sealed class AssemblyBoundaryTests
{
    [Fact]
    public void ApplicationAssemblyHasExpectedIdentity()
    {
        var assemblyName = typeof(ApplicationAssemblyMarker).Assembly.GetName().Name;

        Assert.Equal("FolhasDaMichelly.Application", assemblyName);
    }
}
