using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Local.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase9Incidents : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "incident_audit",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    EventId = table.Column<Guid>(type: "TEXT", nullable: false),
                    IncidentId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Action = table.Column<string>(type: "TEXT", maxLength: 80, nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    TimestampUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_incident_audit", x => new { x.ScopeKey, x.EventId });
                });

            migrationBuilder.CreateTable(
                name: "incidents",
                columns: table => new
                {
                    ScopeKey = table.Column<string>(type: "TEXT", maxLength: 64, nullable: false),
                    IncidentId = table.Column<Guid>(type: "TEXT", nullable: false),
                    DeliveryAttemptId = table.Column<Guid>(type: "TEXT", nullable: false),
                    Status = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    Severity = table.Column<string>(type: "TEXT", maxLength: 40, nullable: false),
                    Version = table.Column<long>(type: "INTEGER", nullable: false),
                    JsonPayload = table.Column<string>(type: "TEXT", nullable: false),
                    DetectedAtUtc = table.Column<long>(type: "INTEGER", nullable: false),
                    UpdatedAtUtc = table.Column<long>(type: "INTEGER", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_incidents", x => new { x.ScopeKey, x.IncidentId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_incident_audit_ScopeKey_IncidentId_TimestampUtc",
                table: "incident_audit",
                columns: new[] { "ScopeKey", "IncidentId", "TimestampUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_incidents_ScopeKey_DeliveryAttemptId",
                table: "incidents",
                columns: new[] { "ScopeKey", "DeliveryAttemptId" });

            migrationBuilder.CreateIndex(
                name: "IX_incidents_ScopeKey_Status_UpdatedAtUtc",
                table: "incidents",
                columns: new[] { "ScopeKey", "Status", "UpdatedAtUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "incident_audit");

            migrationBuilder.DropTable(
                name: "incidents");
        }
    }
}
