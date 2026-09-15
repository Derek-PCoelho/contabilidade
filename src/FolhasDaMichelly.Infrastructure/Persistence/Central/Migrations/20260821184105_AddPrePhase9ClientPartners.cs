using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Central.Migrations
{
    /// <inheritdoc />
    public partial class AddPrePhase9ClientPartners : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "client_partners",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClientId = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    FullName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    CpfNormalized = table.Column<string>(type: "character varying(11)", maxLength: 11, nullable: true),
                    Role = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_client_partners", x => x.Id);
                    table.ForeignKey(
                        name: "FK_client_partners_clients_ClientId",
                        column: x => x.ClientId,
                        principalTable: "clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_client_partners_ClientId_IsActive_FullName",
                table: "client_partners",
                columns: new[] { "ClientId", "IsActive", "FullName" });

            migrationBuilder.CreateIndex(
                name: "IX_client_partners_OrganizationId_CpfNormalized",
                table: "client_partners",
                columns: new[] { "OrganizationId", "CpfNormalized" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "client_partners");
        }
    }
}
