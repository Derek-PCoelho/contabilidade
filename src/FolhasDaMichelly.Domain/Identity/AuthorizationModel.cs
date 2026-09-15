namespace FolhasDaMichelly.Domain.Identity;

public static class AppRoles
{
    public const string OwnerTechnical = "OwnerTechnical";
    public const string Administrator = "Administrator";
    public const string Manager = "Manager";
    public const string Operator = "Operator";
    public const string Auditor = "Auditor";

    public static IReadOnlyList<string> All { get; } =
    [
        OwnerTechnical,
        Administrator,
        Manager,
        Operator,
        Auditor,
    ];
}

public static class AppPermissions
{
    public const string ClientsRead = "clients.read";
    public const string ClientsWrite = "clients.write";
    public const string TemplatesRead = "templates.read";
    public const string TemplatesWrite = "templates.write";
    public const string DocumentsProcess = "documents.process";
    public const string BatchApprove = "batch.approve";
    public const string EmailDraft = "email.draft";
    public const string EmailSend = "email.send";
    public const string AuditRead = "audit.read";
    public const string AuditExport = "audit.export";
    public const string UsersManage = "users.manage";
    public const string RecognitionManage = "recognition.manage";
    public const string SettingsManage = "settings.manage";

    public static IReadOnlyList<string> All { get; } =
    [
        ClientsRead,
        ClientsWrite,
        TemplatesRead,
        TemplatesWrite,
        DocumentsProcess,
        BatchApprove,
        EmailDraft,
        EmailSend,
        AuditRead,
        AuditExport,
        UsersManage,
        RecognitionManage,
        SettingsManage,
    ];
}

public static class RolePermissionCatalog
{
    private static readonly Dictionary<string, IReadOnlySet<string>> PermissionsByRole =
        new Dictionary<string, IReadOnlySet<string>>(StringComparer.Ordinal)
        {
            [AppRoles.OwnerTechnical] = new HashSet<string>(AppPermissions.All, StringComparer.Ordinal),
            [AppRoles.Administrator] = new HashSet<string>(AppPermissions.All, StringComparer.Ordinal),
            [AppRoles.Manager] = new HashSet<string>(
                [
                    AppPermissions.ClientsRead,
                    AppPermissions.ClientsWrite,
                    AppPermissions.TemplatesRead,
                    AppPermissions.TemplatesWrite,
                    AppPermissions.DocumentsProcess,
                    AppPermissions.BatchApprove,
                    AppPermissions.EmailDraft,
                    AppPermissions.EmailSend,
                    AppPermissions.AuditRead,
                    AppPermissions.AuditExport,
                    AppPermissions.UsersManage,
                ],
                StringComparer.Ordinal),
            [AppRoles.Operator] = new HashSet<string>(
                [
                    AppPermissions.ClientsRead,
                    AppPermissions.TemplatesRead,
                    AppPermissions.DocumentsProcess,
                    AppPermissions.EmailDraft,
                ],
                StringComparer.Ordinal),
            [AppRoles.Auditor] = new HashSet<string>(
                [
                    AppPermissions.ClientsRead,
                    AppPermissions.TemplatesRead,
                    AppPermissions.AuditRead,
                    AppPermissions.AuditExport,
                ],
                StringComparer.Ordinal),
        };

    public static IReadOnlySet<string> ForRole(string role) =>
        PermissionsByRole.TryGetValue(role, out var permissions)
            ? permissions
            : new HashSet<string>(StringComparer.Ordinal);
}
