using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace FolhasDaMichelly.Infrastructure.Persistence.Central.Migrations
{
    /// <inheritdoc />
    public partial class AddPartnerOptionalEmail : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.AddColumn<string>(
                name: "EmailNormalized",
                table: "client_partners",
                type: "character varying(254)",
                maxLength: 254,
                nullable: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "EmailNormalized",
                table: "client_partners");
        }
    }
}
