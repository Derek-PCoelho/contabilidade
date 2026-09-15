using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace FolhasDaMichelly.Infrastructure.Persistence.Local;

public sealed class LocalCacheDbContextFactory : IDesignTimeDbContextFactory<LocalCacheDbContext>
{
    public LocalCacheDbContext CreateDbContext(string[] args)
    {
        var builder = new DbContextOptionsBuilder<LocalCacheDbContext>();
        builder.UseSqlite("Data Source=folhas-local-design.db");
        return new LocalCacheDbContext(builder.Options);
    }
}
