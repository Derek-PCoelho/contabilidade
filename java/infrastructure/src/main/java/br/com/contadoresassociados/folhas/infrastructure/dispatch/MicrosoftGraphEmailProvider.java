package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession.EmailAccountSessionException;
import br.com.contadoresassociados.folhas.application.dispatch.EmailProvider;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchAttachmentSnapshot;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderAccount;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderCapabilities;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderResult;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Provedor Microsoft Graph. Idempotência pela propriedade estendida
 * {@code FolhasIdempotency} (mesmo GUID de conjunto do .NET), anexos pequenos por POST e
 * grandes por sessão de upload em blocos de 3,2 MB, cada anexo identificado por
 * {@code contentId = folhas-{sha256}} para retomar sem duplicar.
 *
 * <p>Pendência 5.4: {@code IOException} de anexo, JSON sem campo e falhas do MSAL/cofre são
 * classificadas; nada escapa depois de iniciada a chamada.
 */
public final class MicrosoftGraphEmailProvider implements EmailProvider {

    static final int SIMPLE_ATTACHMENT_LIMIT_BYTES = 3 * 1024 * 1024;
    static final int UPLOAD_CHUNK_SIZE_BYTES = 10 * 320 * 1024;
    static final String IDEMPOTENCY_PROPERTY_ID =
            "String {66f5a359-4659-4830-9070-00040ec6ac6e} Name FolhasIdempotency";

    record GraphMessage(String id, boolean isDraft) {
    }

    private final ProviderHttp http;
    private final ProviderHttp uploadHttp;
    private final EmailAccountSession session;
    private final MicrosoftGraphOptions options;
    private final BooleanSupplier productionRecipientsAllowed;

    public MicrosoftGraphEmailProvider(HttpClient httpClient, EmailAccountSession session,
            MicrosoftGraphOptions options, BooleanSupplier productionRecipientsAllowed) {
        this(httpClient, session, options, productionRecipientsAllowed, ProviderHttp.Sleeper.REAL);
    }

    MicrosoftGraphEmailProvider(HttpClient httpClient, EmailAccountSession session, MicrosoftGraphOptions options,
            BooleanSupplier productionRecipientsAllowed, ProviderHttp.Sleeper sleeper) {
        var client = httpClient == null ? ProviderHttp.defaultClient() : httpClient;
        this.http = new ProviderHttp(client, options.maximumRetryAttempts(), sleeper, Duration.ofSeconds(100));
        this.uploadHttp = new ProviderHttp(client, options.maximumRetryAttempts(), sleeper, Duration.ofSeconds(120));
        this.session = session;
        this.options = options;
        this.productionRecipientsAllowed = productionRecipientsAllowed == null ? () -> false
                : productionRecipientsAllowed;
    }

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.GRAPH_PROVIDER;
    }

    @Override
    public EmailProviderAccount account() {
        try {
            var status = session.status();
            return new EmailProviderAccount(providerKey(),
                    status.accountId() == null ? "microsoft-graph://me" : status.accountId(), status.displayName(),
                    status.configured() && status.connected());
        } catch (RuntimeException e) {
            return new EmailProviderAccount(providerKey(), "microsoft-graph://me", "Microsoft indisponível", false);
        }
    }

    @Override
    public EmailProviderCapabilities capabilities() {
        return new EmailProviderCapabilities(true, true, true, options.maximumAttachmentBytes());
    }

    @Override
    public EmailProviderResult createDraft(EmailEnvelope envelope) {
        try {
            validate(envelope);
            return createOrRecoverDraft(envelope);
        } catch (EmailAccountSessionException e) {
            return authenticationFailure(e, null);
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, null);
        } catch (IOException e) {
            return ProviderSupport.permanent("ATTACHMENT_READ_FAILED",
                    "Não foi possível validar ou ler um anexo aprovado.", null);
        } catch (ProviderHttp.InvalidResponseException e) {
            return ambiguous("PROVIDER_RESPONSE_INVALID", null);
        } catch (RuntimeException e) {
            return ambiguous("PROVIDER_RESULT_UNKNOWN", null);
        }
    }

    @Override
    public EmailProviderResult send(EmailEnvelope envelope) {
        String draftId = null;
        try {
            validate(envelope);
            if (!options.emailSendEnabled()) {
                throw new EmailAccountSessionException("EMAIL_SEND_DISABLED",
                        "O envio pelo Microsoft 365 está desligado nesta instalação.");
            }
            var draft = createOrRecoverDraft(envelope);
            if (draft.state() != DeliveryAttemptState.DRAFT_CREATED || draft.providerDraftId() == null
                    || draft.providerDraftId().isBlank()) {
                return draft;
            }
            draftId = draft.providerDraftId();
            var id = draftId;
            var response = http.send(() -> HttpRequest.newBuilder(api("me/messages/" + ProviderSupport.encode(id)
                    + "/send")).header("Prefer", "IdType=\"ImmutableId\"")
                    .POST(HttpRequest.BodyPublishers.noBody()), false, () -> session.accessToken(true));
            if (response.status() == 202) {
                return new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, draftId, draftId, null, null);
            }
            throw ProviderHttp.ApiException.from(response);
        } catch (EmailAccountSessionException e) {
            return authenticationFailure(e, draftId);
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, draftId);
        } catch (IOException e) {
            // 5.4: no .NET este caso escapava do SendAsync
            return ProviderSupport.permanent("ATTACHMENT_READ_FAILED",
                    "Não foi possível validar ou ler um anexo aprovado.", draftId);
        } catch (RuntimeException e) {
            return ProviderSupport.ambiguous("SEND_AMBIGUOUS",
                    "O resultado do envio Microsoft é desconhecido; reconcilie antes de tentar novamente.", draftId);
        }
    }

    @Override
    public EmailProviderResult reconcile(DeliveryAttempt attempt) {
        try {
            var message = findByOperationKey(ProviderSupport.operationKey(attempt.mode(), attempt.dispatchItemId(),
                    attempt.dispatchFingerprint()));
            if (message == null) {
                return ambiguous("GRAPH_MESSAGE_NOT_FOUND", null);
            }
            return message.isDraft()
                    ? new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, null, message.id(),
                            "GRAPH_DRAFT_CONFIRMED_NOT_SENT",
                            "O Graph confirmou que a mensagem permanece como rascunho; uma nova execução exige ação "
                                    + "humana explícita.")
                    : new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, message.id(), message.id(),
                            null, null);
        } catch (EmailAccountSessionException e) {
            return authenticationFailure(e, attempt.providerDraftId());
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, attempt.providerDraftId());
        } catch (RuntimeException e) {
            return ambiguous("GRAPH_RECONCILIATION_UNAVAILABLE", attempt.providerDraftId());
        }
    }

    private EmailProviderResult createOrRecoverDraft(EmailEnvelope envelope) throws IOException {
        var operationKey = ProviderSupport.operationKey(envelope.operationMode(), envelope.dispatchItemId(),
                envelope.dispatchFingerprint());
        var existing = findByOperationKey(operationKey);
        if (existing != null && !existing.isDraft()) {
            return new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, existing.id(), existing.id(),
                    null, null);
        }
        var draftId = existing == null ? null : existing.id();
        try {
            if (draftId == null || draftId.isBlank()) {
                var payload = messagePayload(envelope, operationKey);
                var response = http.send(() -> json(api("me/messages"), payload), false,
                        () -> session.accessToken(false));
                if (response.status() != 201) {
                    throw ProviderHttp.ApiException.from(response);
                }
                draftId = ProviderHttp.requiredText(response.json(), "id");
            }
            ensureAttachments(draftId, envelope.attachments());
            return new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, null, draftId, null, null);
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, draftId);
        } catch (ProviderHttp.TransportException | ProviderHttp.InvalidResponseException e) {
            return ProviderSupport.ambiguous("PROVIDER_RESULT_UNKNOWN",
                    "O resultado da criação do rascunho/anexo é desconhecido; reconcilie antes de repetir.", draftId);
        }
    }

    private GraphMessage findByOperationKey(String operationKey) {
        var filter = "singleValueExtendedProperties/Any(ep: ep/id eq '" + IDEMPOTENCY_PROPERTY_ID
                + "' and ep/value eq '" + operationKey.replace("'", "''") + "')";
        var expand = "singleValueExtendedProperties($filter=id eq '" + IDEMPOTENCY_PROPERTY_ID + "')";
        var uri = api("me/messages?$select=id,isDraft,sentDateTime&$top=2&$filter=" + ProviderSupport.encode(filter)
                + "&$expand=" + ProviderSupport.encode(expand));
        var response = http.send(() -> get(uri), true, () -> session.accessToken(false));
        if (!response.ok()) {
            throw ProviderHttp.ApiException.from(response);
        }
        var root = response.json();
        var matches = root.path("value");
        if (!matches.isArray()) {
            throw new ProviderHttp.InvalidResponseException("Resposta do Graph sem 'value'.");
        }
        if (matches.size() > 1 || root.hasNonNull("@odata.nextLink")) {
            throw new ProviderHttp.ApiException(409, null);
        }
        if (matches.isEmpty()) {
            return null;
        }
        var first = matches.get(0);
        return new GraphMessage(ProviderHttp.requiredText(first, "id"), first.path("isDraft").asBoolean(false));
    }

    private void ensureAttachments(String draftId, List<DispatchAttachmentSnapshot> attachments) throws IOException {
        var existing = attachmentContentIds(draftId);
        for (var attachment : attachments) {
            ProviderSupport.validateAttachment(attachment);
            var contentId = contentId(attachment.sha256());
            if (existing.contains(contentId)) {
                continue;
            }
            if (attachment.fileSizeBytes() < SIMPLE_ATTACHMENT_LIMIT_BYTES) {
                addSimpleAttachment(draftId, attachment, contentId);
            } else {
                uploadLargeAttachment(draftId, attachment, contentId);
            }
            existing.add(contentId);
        }
    }

    private Set<String> attachmentContentIds(String draftId) {
        var ids = new HashSet<String>();
        URI next = api("me/messages/" + ProviderSupport.encode(draftId)
                + "/attachments?$select=id,name,size,contentId&$top=100");
        while (next != null) {
            var uri = next;
            var response = http.send(() -> get(uri), true, () -> session.accessToken(false));
            if (!response.ok()) {
                throw ProviderHttp.ApiException.from(response);
            }
            var root = response.json();
            for (var item : root.path("value")) {
                var value = item.get("contentId");
                if (value != null && value.isTextual()) {
                    ids.add(value.asText());
                }
            }
            next = validatedNextLink(root.get("@odata.nextLink"));
        }
        return ids;
    }

    private URI validatedNextLink(com.fasterxml.jackson.databind.JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            var uri = URI.create(value.asText());
            var sameScheme = uri.getScheme() != null
                    && uri.getScheme().equalsIgnoreCase(options.apiBaseAddress().getScheme());
            if (!uri.isAbsolute() || !sameScheme || uri.getHost() == null
                    || !uri.getHost().equalsIgnoreCase(options.apiBaseAddress().getHost())) {
                throw new ProviderHttp.InvalidResponseException("nextLink para host inesperado.");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ProviderHttp.InvalidResponseException("nextLink inválido.");
        }
    }

    private void addSimpleAttachment(String draftId, DispatchAttachmentSnapshot attachment, String contentId)
            throws IOException {
        var bytes = ProviderSupport.readValidated(attachment);
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("@odata.type", "#microsoft.graph.fileAttachment");
            payload.put("name", attachment.fileName());
            payload.put("contentType", "application/pdf");
            payload.put("isInline", false);
            payload.put("contentId", contentId);
            payload.put("contentBytes", Base64.getEncoder().encodeToString(bytes));
            var response = http.send(() -> json(api("me/messages/" + ProviderSupport.encode(draftId) + "/attachments"),
                    payload), false, () -> session.accessToken(false));
            if (response.status() != 201) {
                throw ProviderHttp.ApiException.from(response);
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void uploadLargeAttachment(String draftId, DispatchAttachmentSnapshot attachment, String contentId)
            throws IOException {
        var item = new LinkedHashMap<String, Object>();
        item.put("attachmentType", "file");
        item.put("name", attachment.fileName());
        item.put("size", attachment.fileSizeBytes());
        item.put("contentType", "application/pdf");
        item.put("isInline", false);
        item.put("contentId", contentId);
        var sessionResponse = http.send(() -> json(api("me/messages/" + ProviderSupport.encode(draftId)
                + "/attachments/createUploadSession"), Map.of("AttachmentItem", item)), false,
                () -> session.accessToken(false));
        if (sessionResponse.status() != 201) {
            throw ProviderHttp.ApiException.from(sessionResponse);
        }
        var uploadUrl = ProviderHttp.requiredText(sessionResponse.json(), "uploadUrl");
        URI uploadUri;
        try {
            uploadUri = URI.create(uploadUrl);
        } catch (IllegalArgumentException e) {
            throw new ProviderHttp.ApiException(502, null);
        }
        var secure = "https".equalsIgnoreCase(uploadUri.getScheme()) || ("http".equalsIgnoreCase(uploadUri.getScheme())
                && "127.0.0.1".equals(uploadUri.getHost()) && Boolean.getBoolean("folhas.test.allowLoopbackProviders"));
        if (!uploadUri.isAbsolute() || !secure) {
            throw new ProviderHttp.ApiException(502, null);
        }
        var buffer = new byte[UPLOAD_CHUNK_SIZE_BYTES];
        try (var file = new RandomAccessFile(attachment.localPath(), "r")) {
            var total = file.length();
            if (total != attachment.fileSizeBytes()) {
                throw new ProviderSupport.AttachmentChangedException("Attachment changed before upload.");
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            long offset = 0;
            while (offset < total) {
                var count = file.read(buffer, 0, (int) Math.min(buffer.length, total - offset));
                if (count <= 0) {
                    throw new ProviderSupport.AttachmentChangedException("Attachment changed while uploading.");
                }
                digest.update(buffer, 0, count);
                var chunk = Arrays.copyOf(buffer, count);
                var start = offset;
                // o upload da sessão não leva bearer (URL pré-assinada); PUT de faixa é idempotente
                var response = uploadHttp.send(() -> HttpRequest.newBuilder(uploadUri)
                        .header("Content-Type", "application/octet-stream")
                        .header("Content-Range", "bytes " + start + "-" + (start + count - 1) + "/" + total)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk)), true, null);
                Arrays.fill(chunk, (byte) 0);
                if (response.status() != 200 && response.status() != 201 && response.status() != 202) {
                    throw ProviderHttp.ApiException.from(response);
                }
                offset += count;
            }
            var hash = java.util.HexFormat.of().formatHex(digest.digest());
            if (!hash.equalsIgnoreCase(attachment.sha256())) {
                throw new ProviderSupport.AttachmentChangedException("Attachment changed while uploading.");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private void validate(EmailEnvelope envelope) throws IOException {
        if (envelope == null) {
            throw new EmailAccountSessionException("GRAPH_ENVELOPE_INVALID", "O envelope do e-mail está ausente.");
        }
        if (!options.isConfigured() || !providerKey().equals(session.providerKey())) {
            throw new EmailAccountSessionException("GRAPH_NOT_CONFIGURED", "Microsoft Graph não está configurado.");
        }
        if (envelope.scenario() != null && envelope.scenario() != FakeDeliveryScenario.SUCCESS) {
            throw new EmailAccountSessionException("GRAPH_FAKE_SCENARIO_FORBIDDEN",
                    "Cenários simulados pertencem somente ao modo local seguro.");
        }
        GmailEmailProvider.validateRecipients(envelope, options.controlledRecipient(),
                productionRecipientsAllowed.getAsBoolean(), "GRAPH");
        if (envelope.attachments().stream().mapToLong(DispatchAttachmentSnapshot::fileSizeBytes).sum()
                > options.maximumAttachmentBytes()) {
            throw new ProviderSupport.AttachmentChangedException("Attachment limit exceeded.");
        }
    }

    static Map<String, Object> messagePayload(EmailEnvelope envelope, String operationKey) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("subject", envelope.subject());
        payload.put("body", Map.of("contentType", "HTML", "content", envelope.htmlBody() == null ? ""
                : envelope.htmlBody()));
        payload.put("toRecipients", envelope.to().stream().map(MicrosoftGraphEmailProvider::address).toList());
        payload.put("ccRecipients", envelope.cc().stream().map(MicrosoftGraphEmailProvider::address).toList());
        payload.put("internetMessageHeaders", List.of(Map.of("name", "x-folhas-idempotency", "value", operationKey)));
        payload.put("singleValueExtendedProperties",
                List.of(Map.of("id", IDEMPOTENCY_PROPERTY_ID, "value", operationKey)));
        return payload;
    }

    private static Map<String, Object> address(String email) {
        return Map.of("emailAddress", Map.of("address", email));
    }

    static String contentId(String sha256) {
        return "folhas-" + sha256.toLowerCase(Locale.ROOT);
    }

    private URI api(String relative) {
        return options.apiBaseAddress().resolve(relative);
    }

    private static HttpRequest.Builder get(URI uri) {
        return HttpRequest.newBuilder(uri).header("Prefer", "IdType=\"ImmutableId\"").GET();
    }

    private static HttpRequest.Builder json(URI uri, Object body) {
        return HttpRequest.newBuilder(uri).header("Prefer", "IdType=\"ImmutableId\"")
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
    }

    private static EmailProviderResult authenticationFailure(EmailAccountSessionException e, String draftId) {
        return "AUTH_TEMPORARILY_UNAVAILABLE".equals(e.code())
                ? ProviderSupport.transientFailure(e.code(), e.getMessage(), draftId)
                : ProviderSupport.permanent(e.code(), e.getMessage(), draftId);
    }

    static EmailProviderResult mapFailure(ProviderHttp.ApiException e, boolean mayHaveCompleted, String draftId) {
        var status = e.status();
        if (status == 401) {
            return ProviderSupport.permanent("AUTH_REVOKED", "A autorização Microsoft foi rejeitada ou revogada.",
                    draftId);
        }
        if (status == 403) {
            return ProviderSupport.permanent("PROVIDER_REJECTED",
                    "O Microsoft Graph recusou a permissão para esta operação.", draftId);
        }
        if (status == 429) {
            var message = e.retryAfter().map(d -> "O Graph solicitou aguardar " + d.toSeconds() + " segundo(s).")
                    .orElse("O Graph limitou a operação temporariamente.");
            return ProviderSupport.transientFailure("PROVIDER_THROTTLED", message, draftId);
        }
        if (status == 400 || status == 413 || status == 415 || status == 422) {
            return ProviderSupport.permanent("PROVIDER_REJECTED", "O Microsoft Graph rejeitou o conteúdo da operação.",
                    draftId);
        }
        if (mayHaveCompleted) {
            return ProviderSupport.ambiguous("SEND_AMBIGUOUS",
                    "O Graph não confirmou o resultado; reconciliação obrigatória.", draftId);
        }
        if (ProviderHttp.isTransient(status)) {
            return ProviderSupport.transientFailure("PROVIDER_TRANSIENT_FAILURE",
                    "O Graph está temporariamente indisponível.", draftId);
        }
        return ProviderSupport.permanent("PROVIDER_REJECTED", "O Microsoft Graph rejeitou a operação.", draftId);
    }

    private static EmailProviderResult ambiguous(String code, String draftId) {
        return ProviderSupport.ambiguous(code,
                "O resultado Microsoft permanece desconhecido; não tente reenviar sem reconciliação.", draftId);
    }
}
