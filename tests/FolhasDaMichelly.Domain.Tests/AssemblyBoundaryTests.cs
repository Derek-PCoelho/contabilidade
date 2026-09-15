using FolhasDaMichelly.Domain;

namespace FolhasDaMichelly.Domain.Tests;

public sealed class AssemblyBoundaryTests
{
    [Fact]
    public void DomainAssemblyHasExpectedIdentity()
    {
        var assemblyName = typeof(DomainAssemblyMarker).Assembly.GetName().Name;

        Assert.Equal("FolhasDaMichelly.Domain", assemblyName);
    }
}
