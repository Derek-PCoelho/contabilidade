using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Central.Migrations
{
    /// <inheritdoc />
    public partial class AddPhase3ClientCatalog : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.CreateTable(
                name: "clients",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    PersonType = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    LegalNameOrFullName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    PreferredName = table.Column<string>(type: "character varying(160)", maxLength: 160, nullable: true),
                    InternalCode = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: true),
                    PrimaryTaxIdNormalized = table.Column<string>(type: "character varying(14)", maxLength: 14, nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false),
                    DefaultSubjectTemplateId = table.Column<Guid>(type: "uuid", nullable: true),
                    DefaultBodyTemplateId = table.Column<Guid>(type: "uuid", nullable: true),
                    Notes = table.Column<string>(type: "character varying(2000)", maxLength: 2000, nullable: true),
                    CreatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    CreatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    Version = table.Column<long>(type: "bigint", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_clients", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "message_templates",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    ClientId = table.Column<Guid>(type: "uuid", nullable: true),
                    DocumentTypeId = table.Column<Guid>(type: "uuid", nullable: true),
                    Name = table.Column<string>(type: "character varying(160)", maxLength: 160, nullable: false),
                    SubjectTemplate = table.Column<string>(type: "character varying(500)", maxLength: 500, nullable: false),
                    BodyTemplate = table.Column<string>(type: "character varying(20000)", maxLength: 20000, nullable: false),
                    SignatureMode = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    IsDefault = table.Column<bool>(type: "boolean", nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false),
                    Version = table.Column<long>(type: "bigint", nullable: false),
                    UpdatedBy = table.Column<Guid>(type: "uuid", nullable: false),
                    UpdatedAtUtc = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_message_templates", x => x.Id);
                });

            migrationBuilder.CreateTable(
                name: "client_establishments",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClientId = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    CnpjNormalized = table.Column<string>(type: "character varying(14)", maxLength: 14, nullable: false),
                    CnpjRoot = table.Column<string>(type: "character varying(8)", maxLength: 8, nullable: false),
                    LegalName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    DisplayName = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    InternalCode = table.Column<string>(type: "character varying(80)", maxLength: 80, nullable: true),
                    IsHeadOffice = table.Column<bool>(type: "boolean", nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_client_establishments", x => x.Id);
                    table.ForeignKey(
                        name: "FK_client_establishments_clients_ClientId",
                        column: x => x.ClientId,
                        principalTable: "clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "client_identifiers",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClientId = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    Type = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    ValueNormalized = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    SemanticRole = table.Column<string>(type: "character varying(40)", maxLength: 40, nullable: false),
                    Priority = table.Column<int>(type: "integer", nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false),
                    IsUniqueWithinOrganization = table.Column<bool>(type: "boolean", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_client_identifiers", x => x.Id);
                    table.ForeignKey(
                        name: "FK_client_identifiers_clients_ClientId",
                        column: x => x.ClientId,
                        principalTable: "clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateTable(
                name: "client_recipients",
                columns: table => new
                {
                    Id = table.Column<Guid>(type: "uuid", nullable: false),
                    ClientId = table.Column<Guid>(type: "uuid", nullable: false),
                    OrganizationId = table.Column<Guid>(type: "uuid", nullable: false),
                    EstablishmentId = table.Column<Guid>(type: "uuid", nullable: true),
                    DisplayName = table.Column<string>(type: "character varying(160)", maxLength: 160, nullable: false),
                    EmailNormalized = table.Column<string>(type: "character varying(254)", maxLength: 254, nullable: false),
                    DeliveryRole = table.Column<string>(type: "character varying(32)", maxLength: 32, nullable: false),
                    DocumentTypeId = table.Column<Guid>(type: "uuid", nullable: true),
                    IsPrimary = table.Column<bool>(type: "boolean", nullable: false),
                    IsActive = table.Column<bool>(type: "boolean", nullable: false),
                    ValidFrom = table.Column<DateOnly>(type: "date", nullable: true),
                    ValidTo = table.Column<DateOnly>(type: "date", nullable: true)
                },
                constraints: table =>
                {
                    table.PrimaryKey("PK_client_recipients", x => x.Id);
                    table.ForeignKey(
                        name: "FK_client_recipients_clients_ClientId",
                        column: x => x.ClientId,
                        principalTable: "clients",
                        principalColumn: "Id",
                        onDelete: ReferentialAction.Restrict);
                });

            migrationBuilder.CreateIndex(
                name: "IX_client_establishments_ClientId_IsActive",
                table: "client_establishments",
                columns: new[] { "ClientId", "IsActive" });

            migrationBuilder.CreateIndex(
                name: "IX_client_establishments_OrganizationId_CnpjNormalized",
                table: "client_establishments",
                columns: new[] { "OrganizationId", "CnpjNormalized" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_client_identifiers_ClientId_IsActive_Priority",
                table: "client_identifiers",
                columns: new[] { "ClientId", "IsActive", "Priority" });

            migrationBuilder.CreateIndex(
                name: "IX_client_identifiers_OrganizationId_Type_ValueNormalized",
                table: "client_identifiers",
                columns: new[] { "OrganizationId", "Type", "ValueNormalized" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_client_recipients_ClientId_EstablishmentId_EmailNormalized_~",
                table: "client_recipients",
                columns: new[] { "ClientId", "EstablishmentId", "EmailNormalized", "DeliveryRole", "DocumentTypeId" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_client_recipients_ClientId_IsActive",
                table: "client_recipients",
                columns: new[] { "ClientId", "IsActive" });

            migrationBuilder.CreateIndex(
                name: "IX_clients_OrganizationId_InternalCode",
                table: "clients",
                columns: new[] { "OrganizationId", "InternalCode" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_clients_OrganizationId_IsActive_LegalNameOrFullName",
                table: "clients",
                columns: new[] { "OrganizationId", "IsActive", "LegalNameOrFullName" });

            migrationBuilder.CreateIndex(
                name: "IX_clients_OrganizationId_PrimaryTaxIdNormalized",
                table: "clients",
                columns: new[] { "OrganizationId", "PrimaryTaxIdNormalized" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_message_templates_OrganizationId_ClientId_Name",
                table: "message_templates",
                columns: new[] { "OrganizationId", "ClientId", "Name" },
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_message_templates_OrganizationId_IsActive_ClientId",
                table: "message_templates",
                columns: new[] { "OrganizationId", "IsActive", "ClientId" });
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "client_establishments");

            migrationBuilder.DropTable(
                name: "client_identifiers");

            migrationBuilder.DropTable(
                name: "client_recipients");

            migrationBuilder.DropTable(
                name: "message_templates");

            migrationBuilder.DropTable(
                name: "clients");
        }
    }
}
