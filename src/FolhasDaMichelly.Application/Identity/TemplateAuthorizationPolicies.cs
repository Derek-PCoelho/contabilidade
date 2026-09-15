using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Identity;

public static class TemplateAuthorizationPolicies
{
    public const string TemplatesRead = AppPermissions.TemplatesRead;
    public const string TemplatesWrite = AppPermissions.TemplatesWrite;
}
