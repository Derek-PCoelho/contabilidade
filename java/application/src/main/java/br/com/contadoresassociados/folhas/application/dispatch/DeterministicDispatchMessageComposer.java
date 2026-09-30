package br.com.contadoresassociados.folhas.application.dispatch;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentDispatchGroup;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import br.com.contadoresassociados.folhas.domain.clients.EmailAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Composição determinística da mensagem (assunto, corpo, destinatários, anexos e fingerprint).
 *
 * <p>Correções:
 * <ul>
 *   <li>2.10 — {{operador.nome}} usa o nome do usuário autenticado.</li>
 *   <li>2.11 — validação de e-mail estrita do domínio (a versão .NET usava MailAddress permissivo).</li>
 *   <li>5.1 — em modo Send com produção liberada, provedores reais usam os destinatários do
 *       cadastro; Test e piloto continuam restritos ao destinatário controlado.</li>
 *   <li>6.12 — destinatários e modelos específicos por tipo documental passam a valer.</li>
 * </ul>
 */
public final class DeterministicDispatchMessageComposer implements DispatchMessageComposer {

    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    private final ClientCatalogService catalog;
    private final Clock clock;
    private final DispatchWorkflowOptions options;

    public DeterministicDispatchMessageComposer(ClientCatalogService catalog, Clock clock,
            DispatchWorkflowOptions options) {
        this.catalog = catalog;
        this.clock = clock;
        this.options = options;
    }

    @Override
    public Composition compose(DocumentDispatchGroup group, List<ReviewDocument> documents, DispatchOperationMode mode,
            String testDestination, DispatchExecutionContext context) {
        var blocks = new ArrayList<DispatchBlock>();
        var client = catalog.get(group.clientId()).orElse(null);
        if (client == null || !client.isActive()) {
            blocks.add(block("CLIENT_NOT_AVAILABLE", "O cadastro do cliente não está ativo ou disponível."));
            return new Composition(null, blocks);
        }
        var ids = Set.copyOf(group.documentIds());
        var groupDocs = documents.stream().filter(d -> ids.contains(d.id()))
                .sorted(Comparator.comparing(ReviewDocument::fileName, String.CASE_INSENSITIVE_ORDER)).toList();
        if (groupDocs.size() != group.documentIds().size()) {
            blocks.add(block("GROUP_DOCUMENT_MISMATCH", "A composição não contém todos os documentos aprovados."));
        }
        var types = groupDocs.stream().map(ReviewDocument::documentType).collect(Collectors.toSet());
        var typeIds = types.stream().map(DocumentTypeIds::of).collect(Collectors.toSet());
        var today = clock.accountingDate();

        var eligible = client.recipients().stream()
                .filter(r -> r.isActive() && validOn(r, today) && matchesEstablishment(r, group.establishmentId()))
                .filter(r -> r.documentTypeId() == null || typeIds.contains(r.documentTypeId()))
                .toList();
        // Se houver destinatários específicos do tipo, eles substituem os genéricos do mesmo papel.
        var recipients = preferTypeSpecific(eligible).stream()
                .sorted(Comparator.comparing(RecipientModel::isPrimary).reversed()
                        .thenComparing(RecipientModel::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (var r : recipients) {
            if (!EmailAddress.isValid(r.email())) {
                blocks.add(block("RECIPIENT_EMAIL_INVALID", "O destinatário '" + r.displayName()
                        + "' tem e-mail inválido."));
            }
        }
        recipients = recipients.stream().filter(r -> EmailAddress.isValid(r.email())).toList();
        var originalTo = recipients.stream().filter(r -> r.deliveryRole() == DeliveryRoleModel.TO)
                .map(DeterministicDispatchMessageComposer::snapshot).toList();
        var originalCc = recipients.stream().filter(r -> r.deliveryRole() != DeliveryRoleModel.TO)
                .map(DeterministicDispatchMessageComposer::snapshot).toList();
        if (originalTo.isEmpty()) {
            blocks.add(block("RECIPIENT_TO_MISSING",
                    "Nenhum destinatário principal ativo e compatível com este cliente foi encontrado."));
        }

        var external = options.isExternalProvider();
        var realRecipients = external && mode == DispatchOperationMode.SEND && options.productionRolloutReady();
        if (!external && mode == DispatchOperationMode.TEST && !isSafeTestDestination(testDestination)) {
            blocks.add(block("TEST_DESTINATION_INVALID",
                    "O teste seguro exige uma caixa de teste válida (domínio example.invalid)."));
        }
        if (external && !realRecipients && (!options.providerEnabled()
                || !EmailAddress.isValid(options.controlledRecipient()))) {
            blocks.add(block(DispatchWorkflowOptions.GRAPH_PROVIDER.equals(options.providerKey())
                    ? "GRAPH_CONTROLLED_RECIPIENT_REQUIRED" : "GMAIL_CONTROLLED_RECIPIENT_REQUIRED",
                    "A conta de e-mail conectada ainda não tem uma caixa de teste controlada válida."));
        }

        var templates = catalog.templates(client.id(), false);
        var subjectTemplate = selectTemplate(templates, client.defaultSubjectTemplateId(), client.id(), typeIds);
        var bodyTemplate = selectTemplate(templates, client.defaultBodyTemplateId(), client.id(), typeIds);
        if (subjectTemplate == null) {
            blocks.add(block("SUBJECT_TEMPLATE_MISSING", "Nenhum modelo ativo de assunto foi configurado."));
        }
        if (bodyTemplate == null) {
            blocks.add(block("BODY_TEMPLATE_MISSING", "Nenhum modelo ativo de mensagem foi configurado."));
        }
        var total = groupDocs.stream().mapToLong(ReviewDocument::fileSizeBytes).sum();
        if (total > options.maximumAttachmentBytes()) {
            blocks.add(block("ATTACHMENT_LIMIT_EXCEEDED", "Os anexos somam %.2f MB, acima do limite seguro desta conta."
                    .formatted(total / 1_048_576d)));
        }
        if (groupDocs.stream().anyMatch(d -> !Files.exists(Path.of(d.localPath())))) {
            blocks.add(block("ATTACHMENT_NOT_FOUND", "Um ou mais anexos não existem no caminho local aprovado."));
        }
        if (subjectTemplate == null || bodyTemplate == null) {
            return new Composition(null, blocks);
        }

        var values = placeholderValues(client, group, groupDocs, originalTo, context);
        var subject = render(subjectTemplate.subjectTemplate(), values, blocks);
        var textBody = render(bodyTemplate.bodyTemplate(), values, blocks);
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0) {
            blocks.add(block("SUBJECT_HEADER_INJECTION", "O assunto renderizado contém quebra de linha proibida."));
        }
        if (mode == DispatchOperationMode.TEST) {
            subject = "[TESTE — NÃO ENVIAR AO CLIENTE] " + subject;
        }
        if (external && !realRecipients) {
            subject = "[DESTINO CONTROLADO] " + subject;
        }
        var attachments = groupDocs.stream().map(d -> new DispatchAttachmentSnapshot(d.id(), d.localPath(),
                d.fileName(), d.sha256(), d.fileSizeBytes(), d.documentType())).toList();

        List<String> effectiveTo;
        List<String> effectiveCc;
        if (external && !realRecipients) {
            var controlled = options.controlledRecipient();
            effectiveTo = EmailAddress.isValid(controlled) ? List.of(EmailAddress.normalize(controlled)) : List.of();
            effectiveCc = List.of();
        } else if (mode == DispatchOperationMode.TEST) {
            effectiveTo = isSafeTestDestination(testDestination) ? List.of(EmailAddress.normalize(testDestination))
                    : List.of();
            effectiveCc = List.of();
        } else {
            effectiveTo = originalTo.stream().map(DispatchRecipientSnapshot::email).distinct().toList();
            effectiveCc = originalCc.stream().map(DispatchRecipientSnapshot::email)
                    .filter(e -> !effectiveTo.contains(e)).distinct().toList();
        }
        var sender = switch (options.providerKey()) {
            case DispatchWorkflowOptions.GRAPH_PROVIDER -> "microsoft-graph://me";
            case DispatchWorkflowOptions.GMAIL_PROVIDER -> "google-gmail://me";
            default -> options.senderAccountId();
        };
        var html = "<div>" + htmlEncode(textBody).replace("\r\n", "<br>").replace("\n", "<br>") + "</div>";
        var fingerprint = fingerprint(client.id(), group.establishmentId(), sender, mode, subjectTemplate, bodyTemplate,
                originalTo, originalCc, effectiveTo, effectiveCc, subject, textBody, attachments);
        var message = new RenderedMessageSnapshot(subjectTemplate.id(), subjectTemplate.version(),
                subjectTemplate.name(), bodyTemplate.id(), bodyTemplate.version(), bodyTemplate.name(), sender,
                originalTo, originalCc, effectiveTo, effectiveCc, subject, textBody, html, attachments, fingerprint,
                clock.nowUtc());
        return new Composition(message, blocks);
    }

    private static List<RecipientModel> preferTypeSpecific(List<RecipientModel> eligible) {
        var specificRoles = eligible.stream().filter(r -> r.documentTypeId() != null).map(RecipientModel::deliveryRole)
                .collect(Collectors.toSet());
        return eligible.stream().filter(r -> r.documentTypeId() != null || !specificRoles.contains(r.deliveryRole()))
                .toList();
    }

    private static boolean validOn(RecipientModel r, LocalDate today) {
        return (r.validFrom() == null || !r.validFrom().isAfter(today))
                && (r.validTo() == null || !r.validTo().isBefore(today));
    }

    private static boolean matchesEstablishment(RecipientModel r, UUID establishmentId) {
        return establishmentId == null ? r.establishmentId() == null
                : r.establishmentId() == null || r.establishmentId().equals(establishmentId);
    }

    static MessageTemplateModel selectTemplate(List<MessageTemplateModel> templates, UUID preferred, UUID clientId,
            Set<UUID> typeIds) {
        if (preferred != null) {
            return templates.stream().filter(t -> t.id().equals(preferred) && t.isActive()).findFirst().orElse(null);
        }
        return templates.stream().filter(MessageTemplateModel::isActive)
                .filter(t -> t.documentTypeId() == null || typeIds.contains(t.documentTypeId()))
                .sorted(Comparator.<MessageTemplateModel, Boolean>comparing(t -> t.documentTypeId() != null).reversed()
                        .thenComparing(Comparator.<MessageTemplateModel, Boolean>comparing(t -> clientId.equals(t.clientId()))
                                .reversed())
                        .thenComparing(Comparator.comparing(MessageTemplateModel::isDefault).reversed())
                        .thenComparing(MessageTemplateModel::name, String.CASE_INSENSITIVE_ORDER))
                .findFirst().orElse(null);
    }

    private Map<String, String> placeholderValues(ClientDetails client, DocumentDispatchGroup group,
            List<ReviewDocument> docs, List<DispatchRecipientSnapshot> to, DispatchExecutionContext ctx) {
        var preferred = client.preferredName() == null || client.preferredName().isBlank()
                ? client.legalNameOrFullName() : client.preferredName().strip();
        var list = docs.stream().map(d -> "- " + DocumentPresentation.label(d.documentType()) + ": " + d.fileName())
                .collect(Collectors.joining("\n"));
        var dues = docs.stream().map(d -> d.period().dueDate()).filter(Objects::nonNull).distinct().sorted()
                .map(BR::format).toList();
        var operator = ctx == null || ctx.actorDisplayName() == null || ctx.actorDisplayName().isBlank()
                ? "Equipe responsável" : ctx.actorDisplayName().strip();
        var values = new HashMap<String, String>();
        values.put(MessageTemplatePlaceholderCatalog.CLIENT_LEGAL_NAME, client.legalNameOrFullName());
        values.put(MessageTemplatePlaceholderCatalog.CLIENT_PREFERRED_NAME, preferred);
        values.put(MessageTemplatePlaceholderCatalog.CLIENT_PREFERRED_OR_LEGAL_NAME, preferred);
        values.put(MessageTemplatePlaceholderCatalog.CONTACT_NAME, to.isEmpty() ? "Contato responsável"
                : to.getFirst().displayName());
        values.put(MessageTemplatePlaceholderCatalog.PERIOD_LABEL, group.periodLabel());
        values.put(MessageTemplatePlaceholderCatalog.DOCUMENT_LIST, list);
        values.put(MessageTemplatePlaceholderCatalog.DOCUMENT_COUNT, Integer.toString(docs.size()));
        values.put(MessageTemplatePlaceholderCatalog.DUE_DATE_LIST, dues.isEmpty() ? "Não informado"
                : String.join(", ", dues));
        values.put(MessageTemplatePlaceholderCatalog.OPERATOR_NAME, operator);
        values.put(MessageTemplatePlaceholderCatalog.OFFICE_NAME, options.officeName());
        return values;
    }

    private static String render(String template, Map<String, String> values, List<DispatchBlock> blocks) {
        var validation = MessageTemplatePlaceholderCatalog.validate(template);
        var unresolved = new TreeSet<>(validation.unknownKeys());
        var rendered = MessageTemplatePlaceholderCatalog.replaceTokens(template, key -> {
            var v = values.get(key);
            if (v == null || v.isBlank()) {
                unresolved.add(key);
                return null;
            }
            return v;
        });
        unresolved.forEach(k -> blocks.add(block("TEMPLATE_PLACEHOLDER_UNRESOLVED", "Campo não resolvido: " + k + ".")));
        if (!validation.malformed().isEmpty()) {
            blocks.add(block("TEMPLATE_PLACEHOLDER_MALFORMED", "O modelo tem \"{{\" ou \"}}\" sem par."));
        }
        return rendered.strip();
    }

    private static DispatchRecipientSnapshot snapshot(RecipientModel r) {
        return new DispatchRecipientSnapshot(r.id(), r.displayName(), EmailAddress.normalize(r.email()),
                roleName(r.deliveryRole()), r.establishmentId());
    }

    /** Mesmo texto que a versão .NET gravava ("To", "Cc", "InternalCopy"). */
    static String roleName(DeliveryRoleModel role) {
        return switch (role) {
            case TO -> "To";
            case CC -> "Cc";
            case INTERNAL_COPY -> "InternalCopy";
        };
    }

    static boolean isSafeTestDestination(String value) {
        return EmailAddress.tryNormalize(value).map(v -> v.endsWith("@example.invalid")).orElse(false);
    }

    static String htmlEncode(String value) {
        var b = new StringBuilder(value.length() + 16);
        for (var c : value.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String fingerprint(UUID clientId, UUID establishmentId, String sender, DispatchOperationMode mode,
            MessageTemplateModel subject, MessageTemplateModel body, List<DispatchRecipientSnapshot> to,
            List<DispatchRecipientSnapshot> cc, List<String> effTo, List<String> effCc, String subjectText,
            String bodyText, List<DispatchAttachmentSnapshot> attachments) {
        var fields = List.of(hex(clientId), establishmentId == null ? "" : hex(establishmentId), sender, modeName(mode),
                hex(subject.id()), Long.toString(subject.version()), hex(body.id()), Long.toString(body.version()),
                sortedJoin(to.stream().map(DispatchRecipientSnapshot::email).toList()),
                sortedJoin(cc.stream().map(DispatchRecipientSnapshot::email).toList()), sortedJoin(effTo),
                sortedJoin(effCc), subjectText, bodyText.replace("\r\n", "\n"),
                attachments.stream().map(DispatchAttachmentSnapshot::sha256).sorted().collect(Collectors.joining(";")));
        var canonical = fields.stream().map(f -> f.length() + ":" + f).collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String modeName(DispatchOperationMode mode) {
        return switch (mode) {
            case TEST -> "Test";
            case DRAFT -> "Draft";
            case SEND -> "Send";
        };
    }

    private static String sortedJoin(List<String> values) {
        return values.stream().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(";"));
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    private static DispatchBlock block(String code, String message) {
        return new DispatchBlock(code, ValidationSeverity.BLOCKER, message);
    }

    /** Usado pela UI para listar tipos com rótulo. */
    public static Map<UUID, String> documentTypeLabels() {
        var map = new LinkedHashMap<UUID, String>();
        for (var t : RecognizedDocumentType.values()) {
            if (t != RecognizedDocumentType.UNCLASSIFIED) {
                map.put(DocumentTypeIds.of(t), DocumentPresentation.label(t));
            }
        }
        return map;
    }
}
