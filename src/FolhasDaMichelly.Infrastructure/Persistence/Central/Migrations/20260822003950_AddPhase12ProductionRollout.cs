using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Central.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase12ProductionRollout : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "production_dispatch_authorizations",
                columns: table => new
                {
                    OperationId = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    UserId = table.Column<Guid>(type: "uuid", nullable: false),
                    DeviceId = table.Column<Guid>(type: "uuid", nullable: false),
                    ProviderKey = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    DispatchFingerprint = table.Column<string>(type: "character varying(64)", maxLength: 64, nullable: false),
                    BatchSize = table.Column<int>(type: "integer", nullable: false),
                    AttachmentCount = table.Column<int>(type: "integer", nullable: false),
                    ApplicationVersion = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    AuthorizationDateUtc = table.Column<string>(type: "character varying(10)", maxLength: 10, nullable: false),
                    AuthorizedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_production_dispatch_authorizations", x => new { x.OrganizationId, x.OperationId });
                });

            migrationBuilder.CreateIndex(
                name: "IX_production_dispatch_authorizations_OrganizationId_Authoriza~",
                table: "production_dispatch_authorizations",
                columns: new[] { "OrganizationId", "AuthorizationDateUtc" });

            migrationBuilder.CreateIndex(
                name: "IX_production_dispatch_authorizations_OrganizationId_UserId_Au~",
                table: "production_dispatch_authorizations",
                columns: new[] { "OrganizationId", "UserId", "AuthorizationDateUtc" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "production_dispatch_authorizations");
        }
    }
}
