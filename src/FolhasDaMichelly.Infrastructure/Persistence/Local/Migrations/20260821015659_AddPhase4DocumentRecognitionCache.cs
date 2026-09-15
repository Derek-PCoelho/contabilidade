using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase4DocumentRecognitionCache : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "document_recognition_cache",
                columns: table => new
                {
                    Sha256 = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    EngineVersion = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    RecognizedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_document_recognition_cache", x => x.Sha256);
                });

            migrationBuilder.CreateIndex(
                name: "IX_document_recognition_cache_EngineVersion_RecognizedAtUtc",
                table: "document_recognition_cache",
                columns: new[] { "EngineVersion", "RecognizedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "document_recognition_cache");
        }
    }
}
