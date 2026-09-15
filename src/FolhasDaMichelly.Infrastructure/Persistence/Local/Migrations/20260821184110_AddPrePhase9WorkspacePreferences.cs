using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPrePhase9WorkspacePreferences : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "workspace_preferences",
                columns: table => new
                {
                    Key = table.Column<string>(type: "TEXT", maxLength: 80, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_workspace_preferences", x => x.Key);
                });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "workspace_preferences");
        }
    }
}
