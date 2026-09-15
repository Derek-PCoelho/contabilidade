using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace FolhasDaMichelly.Infrastructure.Persistence.Central;

public sealed class FolhasDbContextFactory : IDesignTimeDbContextFactory<FolhasDbContext>
{
    public FolhasDbContext CreateDbContext(string[] args)
    {
        var builder = new DbContextOptionsBuilder<FolhasDbContext>();
        builder.UseNpgsql(
            "Host=127.0.0.1;Port=5432;Database=folhas_design;Username=folhas_design");
        builder.UseOpenIddict<Guid>();
        return new FolhasDbContext(builder.Options);
    }
}
