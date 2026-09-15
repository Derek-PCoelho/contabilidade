using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase3CatalogCache : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "catalog_cache",
                columns: table => new
                {
                    RecordType = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    RecordId = table.Column<Guid>(type: "TEXT", nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    Version = table.Column<long>(type: "INTEGER", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_catalog_cache", x => new { x.RecordType, x.RecordId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_catalog_cache_RecordType_UpdatedAtUtc",
                table: "catalog_cache",
                columns: new[] { "RecordType", "UpdatedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "catalog_cache");
        }
    }
}
