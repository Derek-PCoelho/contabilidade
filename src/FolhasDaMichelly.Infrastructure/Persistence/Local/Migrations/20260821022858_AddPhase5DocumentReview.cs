using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase5DocumentReview : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "document_review_audit",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    EventId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Action = table.Column<string>(type: "TEXT", maxLength: 80, nullable: false),
                    DocumentId = table.Column<Guid>(type: "TEXT", nullable: true),
                    GroupId = table.Column<Guid>(type: "TEXT", nullable: true),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    TimestampUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_document_review_audit", x => new { x.ScopeKey, x.EventId });
                });

            migrationBuilder.CreateTable(
                name: "document_review_groups",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    GroupId = table.Column<Guid>(type: "TEXT", nullable: false),
                    ClientId = table.Column<Guid>(type: "TEXT", nullable: false),
                    PeriodKey = table.Column<string>(type: "TEXT", maxLength: 120, nullable: false),
                    State = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_document_review_groups", x => new { x.ScopeKey, x.GroupId });
                });

            migrationBuilder.CreateTable(
                name: "document_reviews",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    DocumentId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Sha256 = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    SemanticDuplicateKey = table.Column<string>(type: "TEXT", maxLength: 500, nullable: false),
                    State = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_document_reviews", x => new { x.ScopeKey, x.DocumentId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_document_review_audit_ScopeKey_DocumentId",
                table: "document_review_audit",
                columns: new[] { "ScopeKey", "DocumentId" });

            migrationBuilder.CreateIndex(
                name: "IX_document_review_audit_ScopeKey_GroupId",
                table: "document_review_audit",
                columns: new[] { "ScopeKey", "GroupId" });

            migrationBuilder.CreateIndex(
                name: "IX_document_review_audit_ScopeKey_TimestampUtc",
                table: "document_review_audit",
                columns: new[] { "ScopeKey", "TimestampUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_document_review_groups_ScopeKey_ClientId_PeriodKey",
                table: "document_review_groups",
                columns: new[] { "ScopeKey", "ClientId", "PeriodKey" });

            migrationBuilder.CreateIndex(
                name: "IX_document_reviews_ScopeKey_SemanticDuplicateKey",
                table: "document_reviews",
                columns: new[] { "ScopeKey", "SemanticDuplicateKey" });

            migrationBuilder.CreateIndex(
                name: "IX_document_reviews_ScopeKey_Sha256",
                table: "document_reviews",
                columns: new[] { "ScopeKey", "Sha256" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "document_review_audit");

            migrationBuilder.DropTable(
                name: "document_review_groups");

            migrationBuilder.DropTable(
                name: "document_reviews");
        }
    }
}
