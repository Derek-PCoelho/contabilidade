using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class InitialLocalPhase2 : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "cached_clients",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "TEXT", nullable: false),
                    DisplayName = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    IsActive = table.Column<bool>(type: "INTEGER", nullable: false),
                    Version = table.Column<long>(type: "INTEGER", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_cached_clients", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "offline_sync_operations",
                columns: table => new
                {
                    OperationId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ClientId = table.Column<Guid>(type: "TEXT", nullable: false),
                    DisplayName = table.Column<string>(type: "TEXT", maxLength: 200, nullable: false),
                    IsActive = table.Column<bool>(type: "INTEGER", nullable: false),
                    ExpectedVersion = table.Column<long>(type: "INTEGER", nullable: false),
                    AttemptCount = table.Column<int>(type: "INTEGER", nullable: false),
                    NextAttemptAtUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    CreatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    IsConflicted = table.Column<bool>(type: "INTEGER", nullable: false),
                    LastErrorCode = table.Column<string>(type: "TEXT", maxLength: 80, nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_offline_sync_operations", x => x.OperationId);
                });

            migrationBuilder.CreateTable(
                name: "sync_state",
                columns: table => new
                {
                    Id = table.Column<int>(type: "INTEGER", nullable: false)
                        .Annotation("Sqlite:Autoincrement", true),
                    Checkpoint = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_sync_state", x => x.Id);
                });

            migrationBuilder.CreateIndex(
                name: "IX_offline_sync_operations_IsConflicted_NextAttemptAtUtc",
                table: "offline_sync_operations",
                columns: new[] { "IsConflicted", "NextAttemptAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "cached_clients");

            migrationBuilder.DropTable(
                name: "offline_sync_operations");

            migrationBuilder.DropTable(
                name: "sync_state");
        }
    }
}
