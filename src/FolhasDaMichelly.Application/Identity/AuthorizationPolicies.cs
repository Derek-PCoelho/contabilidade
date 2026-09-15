using FolhasDaMichelly.Domain.Identity;

namespace FolhasDaMichelly.Application.Identity;

public static class AuthorizationPolicies
{
    public const string ClientsRead = AppPermissions.ClientsRead;
    public const string ClientsWrite = AppPermissions.ClientsWrite;
    public const string UsersManage = AppPermissions.UsersManage;
    public const string EmailSend = AppPermissions.EmailSend;
}
