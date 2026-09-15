using Microsoft.EntityFrameworkCore;

namespace FolhasDaMichelly.Infrastructure.Identity;

public sealed class OidcClientDbContext(DbContextOptions<OidcClientDbContext> options)
    : DbContext(options)
{
    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        ArgumentNullException.ThrowIfNull(modelBuilder);
        modelBuilder.UseOpenIddict<Guid>();
    }
}
