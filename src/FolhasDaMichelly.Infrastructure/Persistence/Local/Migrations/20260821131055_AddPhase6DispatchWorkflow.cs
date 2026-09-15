using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase6DispatchWorkflow : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "delivery_attempts",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    AttemptId = table.Column<Guid>(type: "TEXT", nullable: false),
                    DispatchItemId = table.Column<Guid>(type: "TEXT", nullable: false),
                    State = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    IdempotencyKey = table.Column<string>(type: "TEXT", maxLength: 180, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    StartedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_delivery_attempts", x => new { x.ScopeKey, x.AttemptId });
                });

            migrationBuilder.CreateTable(
                name: "dispatch_audit",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    EventId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Action = table.Column<string>(type: "TEXT", maxLength: 80, nullable: false),
                    DispatchItemId = table.Column<Guid>(type: "TEXT", nullable: true),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    TimestampUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_dispatch_audit", x => new { x.ScopeKey, x.EventId });
                });

            migrationBuilder.CreateTable(
                name: "dispatch_items",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    DispatchItemId = table.Column<Guid>(type: "TEXT", nullable: false),
                    BatchId = table.Column<Guid>(type: "TEXT", nullable: false),
                    GroupId = table.Column<Guid>(type: "TEXT", nullable: false),
                    State = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    DispatchFingerprint = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_dispatch_items", x => new { x.ScopeKey, x.DispatchItemId });
                });

            migrationBuilder.CreateTable(
                name: "processing_batches",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    BatchId = table.Column<Guid>(type: "TEXT", nullable: false),
                    State = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    OperationMode = table.Column<string>(type: "TEXT", maxLength: 20, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_processing_batches", x => new { x.ScopeKey, x.BatchId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_delivery_attempts_ScopeKey_DispatchItemId",
                table: "delivery_attempts",
                columns: new[] { "ScopeKey", "DispatchItemId" });

            migrationBuilder.CreateIndex(
                name: "IX_delivery_attempts_ScopeKey_IdempotencyKey",
                table: "delivery_attempts",
                columns: new[] { "ScopeKey", "IdempotencyKey" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_dispatch_audit_ScopeKey_DispatchItemId",
                table: "dispatch_audit",
                columns: new[] { "ScopeKey", "DispatchItemId" });

            migrationBuilder.CreateIndex(
                name: "IX_dispatch_audit_ScopeKey_TimestampUtc",
                table: "dispatch_audit",
                columns: new[] { "ScopeKey", "TimestampUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_dispatch_items_ScopeKey_BatchId",
                table: "dispatch_items",
                columns: new[] { "ScopeKey", "BatchId" });

            migrationBuilder.CreateIndex(
                name: "IX_dispatch_items_ScopeKey_DispatchFingerprint",
                table: "dispatch_items",
                columns: new[] { "ScopeKey", "DispatchFingerprint" });

            migrationBuilder.CreateIndex(
                name: "IX_dispatch_items_ScopeKey_GroupId",
                table: "dispatch_items",
                columns: new[] { "ScopeKey", "GroupId" });

            migrationBuilder.CreateIndex(
                name: "IX_processing_batches_ScopeKey_UpdatedAtUtc",
                table: "processing_batches",
                columns: new[] { "ScopeKey", "UpdatedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "delivery_attempts");

            migrationBuilder.DropTable(
                name: "dispatch_audit");

            migrationBuilder.DropTable(
                name: "dispatch_items");

            migrationBuilder.DropTable(
                name: "processing_batches");
        }
    }
}
