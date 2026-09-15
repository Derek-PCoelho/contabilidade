using System.Globalization;
using System.Net;
using System.Net.Mail;
using System.Security.Cryptography;
using System.Text;
using FolhasDaMichelly.Application.Abstractions;
using FolhasDaMichelly.Application.Clients;
using FolhasDaMichelly.Application.Documents;
using FolhasDaMichelly.Contracts.Clients;
using FolhasDaMichelly.Contracts.Dispatch;
using FolhasDaMichelly.Contracts.Documents;

namespace FolhasDaMichelly.Application.Dispatch;

public sealed class DeterministicDispatchMessageComposer(
    IClientCatalogService clientCatalog,
    IDispatchExecutionContextAccessor contextAccessor,
    IClock clock,
    DispatchWorkflowOptions options) : IDispatchMessageComposer
{
    private static readonly StringComparer Comparer = StringComparer.OrdinalIgnoreCase;

    public async Task<(RenderedMessageSnapshot? Message, IReadOnlyList<DispatchBlock> Blocks)> ComposeAsync(
        DocumentDispatchGroup group,
        IReadOnlyList<ReviewDocument> documents,
        DispatchOperationMode mode,
        string? testDestination,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(group);
        ArgumentNullException.ThrowIfNull(documents);
        var blocks = new List<DispatchBlock>();
        var client = await clientCatalog.GetAsync(group.ClientId, cancellationToken);
        if (client is null || !client.IsActive)
        {
            blocks.Add(Block("CLIENT_NOT_AVAILABLE", "O cadastro do cliente não está ativo ou disponível."));
            return (null, blocks);
        }

        var groupDocuments = documents
            .Where(document => group.DocumentIds.Contains(document.Id))
            .OrderBy(document => document.FileName, StringComparer.OrdinalIgnoreCase)
            .ToArray();
        if (groupDocuments.Length != group.DocumentIds.Count)
        {
            blocks.Add(Block("GROUP_DOCUMENT_MISMATCH", "A composição não contém todos os documentos aprovados."));
        }

        var today = DateOnly.FromDateTime(clock.UtcNow.UtcDateTime);
        var recipients = client.Recipients
            .Where(recipient => recipient.IsActive &&
                (recipient.ValidFrom is null || recipient.ValidFrom <= today) &&
                (recipient.ValidTo is null || recipient.ValidTo >= today) &&
                recipient.DocumentTypeId is null &&
                RecipientMatchesEstablishment(recipient, group.EstablishmentId))
            .OrderByDescending(recipient => recipient.IsPrimary)
            .ThenBy(recipient => recipient.DisplayName, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        foreach (var recipient in recipients.Where(recipient => !IsValidEmail(recipient.Email)))
        {
            blocks.Add(Block("RECIPIENT_EMAIL_INVALID", $"Destinatário '{recipient.DisplayName}' possui e-mail inválido."));
        }

        recipients = recipients.Where(recipient => IsValidEmail(recipient.Email)).ToArray();
        var originalTo = recipients
            .Where(recipient => recipient.DeliveryRole == DeliveryRoleModel.To)
            .Select(ToSnapshot)
            .ToArray();
        var originalCc = recipients
            .Where(recipient => recipient.DeliveryRole is DeliveryRoleModel.Cc or DeliveryRoleModel.InternalCopy)
            .Select(ToSnapshot)
            .ToArray();
        if (originalTo.Length == 0)
        {
            blocks.Add(Block(
                "RECIPIENT_TO_MISSING",
                "Nenhum destinatário principal ativo e compatível com este cliente foi encontrado."));
        }

        var isMicrosoftGraph = options.ProviderKey == DispatchWorkflowOptions.MicrosoftGraphProviderKey;
        var isGmail = options.ProviderKey == DispatchWorkflowOptions.GmailProviderKey;
        var isExternalProvider = isMicrosoftGraph || isGmail;
        if (!isExternalProvider && mode == DispatchOperationMode.Test && !IsSafeTestDestination(testDestination))
        {
            blocks.Add(Block(
                "TEST_DESTINATION_INVALID",
                "O teste seguro exige uma caixa de teste válida configurada pelo aplicativo."));
        }

        if (isMicrosoftGraph &&
            (!options.MicrosoftGraphEnabled || !IsValidEmail(options.MicrosoftGraphControlledRecipient)))
        {
            blocks.Add(Block(
                "GRAPH_CONTROLLED_RECIPIENT_REQUIRED",
                "A conexão Microsoft 365 ainda não possui uma caixa de teste controlada válida."));
        }

        if (isGmail &&
            (!options.GmailEnabled || !IsValidEmail(options.GmailControlledRecipient)))
        {
            blocks.Add(Block(
                "GMAIL_CONTROLLED_RECIPIENT_REQUIRED",
                "A conexão Google Gmail ainda não possui uma caixa de teste controlada válida."));
        }

        var templates = await clientCatalog.GetTemplatesAsync(client.Id, false, cancellationToken);
        var subjectTemplate = SelectTemplate(templates, client.DefaultSubjectTemplateId, client.Id);
        var bodyTemplate = SelectTemplate(templates, client.DefaultBodyTemplateId, client.Id);
        if (subjectTemplate is null)
        {
            blocks.Add(Block("SUBJECT_TEMPLATE_MISSING", "Nenhum modelo ativo de assunto foi configurado."));
        }

        if (bodyTemplate is null)
        {
            blocks.Add(Block("BODY_TEMPLATE_MISSING", "Nenhum modelo ativo de mensagem foi configurado."));
        }

        var totalAttachmentBytes = groupDocuments.Sum(document => document.FileSizeBytes);
        if (totalAttachmentBytes > options.MaximumAttachmentBytes)
        {
            blocks.Add(Block(
                "ATTACHMENT_LIMIT_EXCEEDED",
                $"Os anexos somam {FormatMegabytes(totalAttachmentBytes)}, acima do limite seguro desta conta."));
        }

        if (groupDocuments.Any(document => !File.Exists(document.LocalPath)))
        {
            blocks.Add(Block("ATTACHMENT_NOT_FOUND", "Um ou mais anexos não existem no caminho local aprovado."));
        }

        if (subjectTemplate is null || bodyTemplate is null)
        {
            return (null, blocks);
        }

        _ = await contextAccessor.GetCurrentAsync(cancellationToken);
        var values = CreatePlaceholderValues(client, group, groupDocuments, originalTo);
        var subject = Render(subjectTemplate.SubjectTemplate, values, blocks);
        var textBody = Render(bodyTemplate.BodyTemplate, values, blocks);
        if (subject.Contains('\r') || subject.Contains('\n'))
        {
            blocks.Add(Block("SUBJECT_HEADER_INJECTION", "O assunto renderizado contém quebra de linha proibida."));
        }

        if (mode == DispatchOperationMode.Test)
        {
            subject = $"[TESTE — NÃO ENVIAR AO CLIENTE] {subject}";
        }

        if (isMicrosoftGraph || isGmail)
        {
            subject = $"[DESTINO CONTROLADO] {subject}";
        }

        var attachments = groupDocuments.Select(document => new DispatchAttachmentSnapshot(
            document.Id,
            document.LocalPath,
            document.FileName,
            document.Sha256,
            document.FileSizeBytes,
            document.DocumentType)).ToArray();
        var controlledRecipient = isMicrosoftGraph
            ? options.MicrosoftGraphControlledRecipient
            : isGmail
                ? options.GmailControlledRecipient
                : null;
        var effectiveTo = isExternalProvider
            ? IsValidEmail(controlledRecipient)
                ? new[] { controlledRecipient!.Trim().ToLowerInvariant() }
                : []
            : mode == DispatchOperationMode.Test
                ? new[] { testDestination!.Trim().ToLowerInvariant() }
            : originalTo.Select(recipient => recipient.Email).Distinct(Comparer).ToArray();
        var effectiveCc = isExternalProvider || mode == DispatchOperationMode.Test
            ? []
            : originalCc.Select(recipient => recipient.Email).Distinct(Comparer).ToArray();
        var senderAccountId = isMicrosoftGraph
            ? "microsoft-graph://me"
            : isGmail
                ? "google-gmail://me"
                : options.SenderAccountId;
        var htmlBody = $"<div>{WebUtility.HtmlEncode(textBody).Replace("\r\n", "<br>", StringComparison.Ordinal).Replace("\n", "<br>", StringComparison.Ordinal)}</div>";
        var fingerprint = ComputeFingerprint(
            client.Id,
            group.EstablishmentId,
            senderAccountId,
            mode,
            subjectTemplate.Id,
            subjectTemplate.Version,
            bodyTemplate.Id,
            bodyTemplate.Version,
            originalTo,
            originalCc,
            effectiveTo,
            effectiveCc,
            subject,
            textBody,
            attachments);
        return (new RenderedMessageSnapshot(
            subjectTemplate.Id,
            subjectTemplate.Version,
            subjectTemplate.Name,
            bodyTemplate.Id,
            bodyTemplate.Version,
            bodyTemplate.Name,
            senderAccountId,
            originalTo,
            originalCc,
            effectiveTo,
            effectiveCc,
            subject,
            textBody,
            htmlBody,
            attachments,
            fingerprint,
            clock.UtcNow), blocks);
    }

    private static bool RecipientMatchesEstablishment(RecipientModel recipient, Guid? establishmentId) =>
        establishmentId is null
            ? recipient.EstablishmentId is null
            : recipient.EstablishmentId is null || recipient.EstablishmentId == establishmentId;

    private static MessageTemplateModel? SelectTemplate(
        IReadOnlyList<MessageTemplateModel> templates,
        Guid? preferredId,
        Guid clientId) =>
        preferredId is not null
            ? templates.FirstOrDefault(template => template.Id == preferredId && template.IsActive)
            : templates
                .Where(template => template.IsActive && template.DocumentTypeId is null)
                .OrderByDescending(template => template.ClientId == clientId)
                .ThenByDescending(template => template.IsDefault)
                .ThenBy(template => template.Name, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault();

    private Dictionary<string, string> CreatePlaceholderValues(
        ClientDetails client,
        DocumentDispatchGroup group,
        ReviewDocument[] documents,
        DispatchRecipientSnapshot[] originalTo)
    {
        var preferred = string.IsNullOrWhiteSpace(client.PreferredName)
            ? client.LegalNameOrFullName
            : client.PreferredName.Trim();
        var documentList = string.Join(
            Environment.NewLine,
            documents.Select(document =>
                $"- {DocumentPresentation.ToPortugueseLabel(document.DocumentType)}: {document.FileName}"));
        var dueDates = documents
            .Where(document => document.Period.DueDate is not null)
            .Select(document => document.Period.DueDate!.Value)
            .Distinct()
            .Order()
            .Select(date => date.ToString("dd/MM/yyyy", CultureInfo.GetCultureInfo("pt-BR")))
            .ToArray();
        return new Dictionary<string, string>(StringComparer.Ordinal)
        {
            [MessageTemplatePlaceholderCatalog.ClientLegalNameKey] = client.LegalNameOrFullName,
            [MessageTemplatePlaceholderCatalog.ClientPreferredNameKey] = preferred,
            [MessageTemplatePlaceholderCatalog.ClientPreferredOrLegalNameKey] = preferred,
            [MessageTemplatePlaceholderCatalog.ContactNameKey] = originalTo.Length == 0
                ? "Contato responsável"
                : originalTo[0].DisplayName,
            [MessageTemplatePlaceholderCatalog.PeriodLabelKey] = group.PeriodLabel,
            [MessageTemplatePlaceholderCatalog.DocumentListKey] = documentList,
            [MessageTemplatePlaceholderCatalog.DocumentCountKey] =
                documents.Length.ToString(CultureInfo.InvariantCulture),
            [MessageTemplatePlaceholderCatalog.DueDateListKey] = dueDates.Length == 0
                ? "Não informado"
                : string.Join(", ", dueDates),
            [MessageTemplatePlaceholderCatalog.OperatorNameKey] = "Equipe responsável",
            [MessageTemplatePlaceholderCatalog.OfficeNameKey] = options.OfficeName,
        };
    }

    private static string FormatMegabytes(long bytes) =>
        $"{bytes / 1_048_576d:0.##} MB";

    private static string Render(
        string template,
        Dictionary<string, string> values,
        List<DispatchBlock> blocks)
    {
        var validation = MessageTemplatePlaceholderCatalog.Validate(template);
        var unresolved = validation.UnknownKeys.ToHashSet(StringComparer.Ordinal);
        var rendered = MessageTemplatePlaceholderCatalog.ReplaceTokens(template, key =>
        {
            if (values.TryGetValue(key, out var value) && !string.IsNullOrWhiteSpace(value))
            {
                return value;
            }

            unresolved.Add(key);
            return null;
        });
        foreach (var key in unresolved.Order(StringComparer.Ordinal))
        {
            blocks.Add(Block("TEMPLATE_PLACEHOLDER_UNRESOLVED", $"Placeholder não resolvido: {key}."));
        }

        return rendered.Trim();
    }

    private static DispatchRecipientSnapshot ToSnapshot(RecipientModel recipient) => new(
        recipient.Id,
        recipient.DisplayName,
        recipient.Email.Trim().ToLowerInvariant(),
        recipient.DeliveryRole.ToString(),
        recipient.EstablishmentId);

    private static bool IsValidEmail(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Contains('\r') || value.Contains('\n'))
        {
            return false;
        }

        try
        {
            var address = new MailAddress(value.Trim());
            return Comparer.Equals(address.Address, value.Trim());
        }
        catch (FormatException)
        {
            return false;
        }
    }

    private static bool IsSafeTestDestination(string? value)
    {
        if (!IsValidEmail(value))
        {
            return false;
        }

        var atIndex = value!.LastIndexOf('@');
        return atIndex >= 0 && Comparer.Equals(value[(atIndex + 1)..], "example.invalid");
    }

    private static string ComputeFingerprint(
        Guid clientId,
        Guid? establishmentId,
        string senderAccount,
        DispatchOperationMode mode,
        Guid subjectTemplateId,
        long subjectTemplateVersion,
        Guid bodyTemplateId,
        long bodyTemplateVersion,
        IReadOnlyList<DispatchRecipientSnapshot> originalTo,
        IReadOnlyList<DispatchRecipientSnapshot> originalCc,
        IReadOnlyList<string> effectiveTo,
        IReadOnlyList<string> effectiveCc,
        string subject,
        string textBody,
        IReadOnlyList<DispatchAttachmentSnapshot> attachments)
    {
        var fields = new[]
        {
            clientId.ToString("N"),
            establishmentId?.ToString("N") ?? string.Empty,
            senderAccount,
            mode.ToString(),
            subjectTemplateId.ToString("N"),
            subjectTemplateVersion.ToString(CultureInfo.InvariantCulture),
            bodyTemplateId.ToString("N"),
            bodyTemplateVersion.ToString(CultureInfo.InvariantCulture),
            string.Join(';', originalTo.Select(recipient => recipient.Email).Order(StringComparer.OrdinalIgnoreCase)),
            string.Join(';', originalCc.Select(recipient => recipient.Email).Order(StringComparer.OrdinalIgnoreCase)),
            string.Join(';', effectiveTo.Order(StringComparer.OrdinalIgnoreCase)),
            string.Join(';', effectiveCc.Order(StringComparer.OrdinalIgnoreCase)),
            subject,
            textBody.Replace("\r\n", "\n", StringComparison.Ordinal),
            string.Join(';', attachments.Select(attachment => attachment.Sha256).Order(StringComparer.Ordinal)),
        };
        var canonical = string.Join('|', fields.Select(field => $"{field.Length}:{field}"));
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(canonical))).ToLowerInvariant();
    }

    private static DispatchBlock Block(string code, string message) =>
        new(code, ValidationSeverity.Blocker, message);
}
