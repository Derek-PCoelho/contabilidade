package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession;
import br.com.contadoresassociados.folhas.application.dispatch.EmailAccountSession.EmailAccountSessionException;
import br.com.contadoresassociados.folhas.application.dispatch.EmailProvider;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderAccount;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderCapabilities;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderResult;
import br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Provedor Gmail (API REST {@code gmail/v1}). Mesmo contrato de idempotência do .NET:
 * Message-ID determinístico {@code <folhas.{modo}.{itemIdN}.{fp[:32]}@folhas.invalid>} e
 * cabeçalho {@code X-Folhas-Idempotency}.
 *
 * <p>Correções: 5.3 (deduplicação e reconciliação também procuram em "Enviados", provando o
 * envio e evitando duplicidade); 5.4 (catch de borda: nenhuma exceção escapa depois de iniciada a
 * chamada — vira {@code AMBIGUOUS} ou falha classificada); 5.5 (upload resumable acima do
 * limite configurado, sem o base64 de +33% dentro do JSON); 5.1 (destinatários reais só no modo
 * Send com o rollout de produção liberado).
 */
public final class GmailEmailProvider implements EmailProvider {

    private static final int MAX_RECIPIENTS = 50;

    record Draft(String draftId, String messageId) {
    }

    private final ProviderHttp http;
    private final EmailAccountSession session;
    private final GmailOptions options;
    private final BooleanSupplier productionRecipientsAllowed;

    public GmailEmailProvider(HttpClient httpClient, EmailAccountSession session, GmailOptions options,
            BooleanSupplier productionRecipientsAllowed) {
        this(httpClient, session, options, productionRecipientsAllowed, ProviderHttp.Sleeper.REAL);
    }

    GmailEmailProvider(HttpClient httpClient, EmailAccountSession session, GmailOptions options,
            BooleanSupplier productionRecipientsAllowed, ProviderHttp.Sleeper sleeper) {
        this.http = new ProviderHttp(httpClient == null ? ProviderHttp.defaultClient() : httpClient,
                options.maximumRetryAttempts(), sleeper, Duration.ofSeconds(120));
        this.session = session;
        this.options = options;
        this.productionRecipientsAllowed = productionRecipientsAllowed == null ? () -> false
                : productionRecipientsAllowed;
    }

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.GMAIL_PROVIDER;
    }

    @Override
    public EmailProviderAccount account() {
        try {
            var status = session.status();
            return new EmailProviderAccount(providerKey(),
                    status.accountId() == null ? "google-gmail://me" : status.accountId(), status.displayName(),
                    status.configured() && status.connected());
        } catch (RuntimeException e) {
            return new EmailProviderAccount(providerKey(), "google-gmail://me", "Google indisponível", false);
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
        } catch (ProviderSupport.AttachmentChangedException e) {
            return ProviderSupport.permanent("ATTACHMENT_READ_FAILED",
                    "Não foi possível validar ou ler um anexo aprovado.", null);
        } catch (IOException e) {
            return ProviderSupport.permanent("ATTACHMENT_READ_FAILED",
                    "Não foi possível validar ou ler um anexo aprovado.", null);
        } catch (RuntimeException e) {
            return ambiguous("GMAIL_DRAFT_RESULT_UNKNOWN", null);
        }
    }

    @Override
    public EmailProviderResult send(EmailEnvelope envelope) {
        String draftId = null;
        try {
            validate(envelope);
            if (!options.emailSendEnabled()) {
                throw new EmailAccountSessionException("EMAIL_SEND_DISABLED",
                        "O envio pelo Gmail está desligado nesta instalação.");
            }
            var draft = createOrRecoverDraft(envelope);
            if (draft.state() != DeliveryAttemptState.DRAFT_CREATED || draft.providerDraftId() == null
                    || draft.providerDraftId().isBlank()) {
                return draft;
            }
            draftId = draft.providerDraftId();
            var id = draftId;
            var response = http.send(() -> json(api("users/me/drafts/send"), Map.of("id", id)), false,
                    () -> session.accessToken(true));
            if (response.status() != 200) {
                throw ProviderHttp.ApiException.from(response);
            }
            var messageId = ProviderHttp.requiredText(response.json(), "id");
            return new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, messageId, draftId, null, null);
        } catch (EmailAccountSessionException e) {
            return authenticationFailure(e, draftId);
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, draftId);
        } catch (IOException e) {
            return ProviderSupport.permanent("ATTACHMENT_READ_FAILED",
                    "Não foi possível validar ou ler um anexo aprovado.", draftId);
        } catch (RuntimeException e) {
            return ambiguous("GMAIL_SEND_RESULT_UNKNOWN", draftId);
        }
    }

    @Override
    public EmailProviderResult reconcile(DeliveryAttempt attempt) {
        var draftId = attempt.providerDraftId();
        try {
            var messageId = messageId(attempt.mode(), attempt.dispatchItemId(), attempt.dispatchFingerprint());
            if (draftId != null && !draftId.isBlank()) {
                var response = http.send(() -> HttpRequest.newBuilder(
                        api("users/me/drafts/" + ProviderSupport.encode(draftId) + "?format=minimal")).GET(), true,
                        () -> session.accessToken(false));
                if (response.status() == 200) {
                    return draftConfirmed(draftId);
                }
                if (response.status() != 404) {
                    throw ProviderHttp.ApiException.from(response);
                }
                // 5.3: o rascunho sumiu — foi enviado (prova em "Enviados") ou apagado?
                var sent = findSentMessage(messageId);
                return sent == null ? ambiguous("GMAIL_SENT_OR_DELETED_UNKNOWN", draftId) : accepted(sent, draftId);
            }
            var draft = findDraft(messageId);
            if (draft != null) {
                return draftConfirmed(draft.draftId());
            }
            var sent = findSentMessage(messageId);
            return sent == null ? ambiguous("GMAIL_DRAFT_NOT_FOUND", null) : accepted(sent, null);
        } catch (EmailAccountSessionException e) {
            return authenticationFailure(e, draftId);
        } catch (ProviderHttp.ApiException e) {
            return mapFailure(e, true, draftId);
        } catch (RuntimeException e) {
            return ambiguous("GMAIL_RECONCILIATION_UNAVAILABLE", draftId);
        }
    }

    private EmailProviderResult createOrRecoverDraft(EmailEnvelope envelope) throws IOException {
        var messageId = messageId(envelope.operationMode(), envelope.dispatchItemId(), envelope.dispatchFingerprint());
        // 5.3: se já foi enviado, nunca criar outro rascunho (evita envio duplicado)
        var sent = findSentMessage(messageId);
        if (sent != null) {
            return accepted(sent, null);
        }
        var existing = findDraft(messageId);
        if (existing != null) {
            return new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, existing.messageId(), existing.draftId(),
                    null, null);
        }
        var mime = mime(envelope, messageId);
        try {
            ProviderHttp.Response response;
            if (mime.length > options.resumableThresholdBytes()) {
                response = resumableUpload(mime);
            } else {
                var raw = ProviderSupport.base64Url(mime);
                response = http.send(() -> json(api("users/me/drafts"), Map.of("message", Map.of("raw", raw))), false,
                        () -> session.accessToken(false));
            }
            if (response.status() != 200) {
                throw ProviderHttp.ApiException.from(response);
            }
            var root = response.json();
            var draftId = ProviderHttp.requiredText(root, "id");
            var providerMessageId = root.has("message") ? ProviderHttp.requiredText(root.get("message"), "id") : null;
            return new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, providerMessageId, draftId, null, null);
        } finally {
            Arrays.fill(mime, (byte) 0);
        }
    }

    /** Pendência 5.5: {@code uploadType=resumable} — sessão + PUT do RFC 822 bruto. */
    private ProviderHttp.Response resumableUpload(byte[] mime) {
        var start = http.send(() -> HttpRequest.newBuilder(upload("users/me/drafts?uploadType=resumable"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("X-Upload-Content-Type", "message/rfc822")
                .header("X-Upload-Content-Length", Long.toString(mime.length))
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(Map.of()))), false,
                () -> session.accessToken(false));
        if (start.status() != 200) {
            throw ProviderHttp.ApiException.from(start);
        }
        var location = start.header("Location").map(URI::create)
                .orElseThrow(() -> new ProviderHttp.InvalidResponseException("Sessão de upload sem Location."));
        if (!sameOrigin(location, options.uploadBaseAddress())) {
            throw new ProviderHttp.InvalidResponseException("Sessão de upload aponta para outro host.");
        }
        return http.send(() -> HttpRequest.newBuilder(location).header("Content-Type", "message/rfc822")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(mime)), false, () -> session.accessToken(false));
    }

    private Draft findDraft(String messageId) {
        var uri = api("users/me/drafts?maxResults=2&q=" + ProviderSupport.encode("rfc822msgid:" + messageId));
        var response = http.send(() -> HttpRequest.newBuilder(uri).GET(), true, () -> session.accessToken(false));
        if (!response.ok()) {
            throw ProviderHttp.ApiException.from(response);
        }
        var root = response.json();
        var drafts = root.get("drafts");
        if (drafts == null || !drafts.isArray() || drafts.isEmpty()) {
            return null;
        }
        if (drafts.size() > 1 || root.hasNonNull("nextPageToken")) {
            throw new ProviderHttp.ApiException(409, null);
        }
        var first = drafts.get(0);
        return new Draft(ProviderHttp.requiredText(first, "id"), ProviderHttp.requiredText(first.get("message"), "id"));
    }

    /**
     * Pendência 5.3: mensagem com o Message-ID determinístico já presente em "Enviados".
     * Devolve {@code null} quando a verificação não está habilitada ou o escopo foi negado (403) —
     * nesse caso vale o comportamento anterior e a reconciliação permanece ambígua.
     */
    private String findSentMessage(String messageId) {
        if (!options.sentVerificationEnabled()) {
            return null;
        }
        var uri = api("users/me/messages?maxResults=2&includeSpamTrash=false&q="
                + ProviderSupport.encode("in:sent rfc822msgid:" + messageId));
        var response = http.send(() -> HttpRequest.newBuilder(uri).GET(), true, () -> session.accessToken(false));
        if (response.status() == 403) {
            return null;
        }
        if (!response.ok()) {
            throw ProviderHttp.ApiException.from(response);
        }
        var root = response.json();
        var messages = root.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return null;
        }
        if (messages.size() > 1 || root.hasNonNull("nextPageToken")) {
            throw new ProviderHttp.ApiException(409, null);
        }
        return ProviderHttp.requiredText(messages.get(0), "id");
    }

    private byte[] mime(EmailEnvelope envelope, String messageId) throws IOException {
        var status = session.status();
        if (!status.connected() || status.accountId() == null || status.accountId().isBlank()) {
            throw new EmailAccountSessionException("AUTH_REQUIRED", "Conecte uma conta Google dedicada.");
        }
        try {
            var message = new FixedIdMimeMessage(Session.getInstance(new Properties()), messageId);
            message.setFrom(new InternetAddress(status.accountId(), true));
            message.setRecipients(jakarta.mail.Message.RecipientType.TO, addresses(envelope.to()));
            if (!envelope.cc().isEmpty()) {
                message.setRecipients(jakarta.mail.Message.RecipientType.CC, addresses(envelope.cc()));
            }
            message.setSubject(envelope.subject() == null ? "" : envelope.subject(), "UTF-8");
            message.setHeader("X-Folhas-Idempotency",
                    ProviderSupport.operationKey(envelope.operationMode(), envelope.dispatchItemId(),
                            envelope.dispatchFingerprint()));

            var alternative = new MimeMultipart("alternative");
            var text = new MimeBodyPart();
            text.setText(envelope.textBody() == null ? "" : envelope.textBody(), "UTF-8", "plain");
            alternative.addBodyPart(text);
            if (envelope.htmlBody() != null && !envelope.htmlBody().isBlank()) {
                var html = new MimeBodyPart();
                html.setText(envelope.htmlBody(), "UTF-8", "html");
                alternative.addBodyPart(html);
            }
            var bodyPart = new MimeBodyPart();
            bodyPart.setContent(alternative);

            var mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(bodyPart);
            for (var attachment : envelope.attachments()) {
                var bytes = ProviderSupport.readValidated(attachment);
                var part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(bytes, "application/pdf")));
                part.setFileName(MimeUtility.encodeText(attachment.fileName(), "UTF-8", null));
                part.setDisposition(MimeBodyPart.ATTACHMENT);
                part.setHeader("Content-Transfer-Encoding", "base64");
                mixed.addBodyPart(part);
            }
            message.setContent(mixed);
            message.saveChanges();
            var out = new ByteArrayOutputStream();
            message.writeTo(out);
            return out.toByteArray();
        } catch (UnsupportedEncodingException e) {
            throw new IOException(e);
        } catch (MessagingException e) {
            throw new EmailAccountSessionException("GMAIL_MESSAGE_INVALID",
                    "Não foi possível montar a mensagem com os endereços informados.", e);
        }
    }

    private static InternetAddress[] addresses(List<String> values) throws MessagingException {
        var list = new ArrayList<InternetAddress>();
        for (var value : values) {
            list.add(new InternetAddress(value.strip(), true));
        }
        return list.toArray(InternetAddress[]::new);
    }

    /** Validação antes de qualquer chamada externa (falhas aqui são permanentes e seguras). */
    private void validate(EmailEnvelope envelope) throws IOException {
        if (envelope == null) {
            throw new EmailAccountSessionException("GMAIL_ENVELOPE_INVALID", "O envelope do e-mail está ausente.");
        }
        if (!options.isConfigured() || !providerKey().equals(session.providerKey())) {
            throw new EmailAccountSessionException("GMAIL_NOT_CONFIGURED", "O Gmail não está configurado.");
        }
        if (envelope.scenario() != null && envelope.scenario() != FakeDeliveryScenario.SUCCESS) {
            throw new EmailAccountSessionException("GMAIL_FAKE_SCENARIO_FORBIDDEN",
                    "Cenários simulados pertencem somente ao modo local seguro.");
        }
        validateRecipients(envelope, options.controlledRecipient(), productionRecipientsAllowed.getAsBoolean(),
                "GMAIL");
        var total = envelope.attachments().stream().mapToLong(a -> a.fileSizeBytes()).sum();
        if (total > options.maximumAttachmentBytes()) {
            throw new ProviderSupport.AttachmentChangedException("Attachment limit exceeded.");
        }
        for (var attachment : envelope.attachments()) {
            ProviderSupport.validateAttachment(attachment);
        }
    }

    /**
     * Regra de destinatários comum a Gmail e Graph (5.1): fora do modo Send com rollout liberado,
     * exatamente um destinatário, igual à caixa controlada; no modo produção, endereços válidos e
     * limite de destinatários.
     */
    static void validateRecipients(EmailEnvelope envelope, String controlled, boolean productionAllowed,
            String prefix) {
        var all = new ArrayList<String>(envelope.to());
        all.addAll(envelope.cc());
        var production = productionAllowed && envelope.operationMode() == DispatchOperationMode.SEND;
        if (!production) {
            if (all.size() != 1 || envelope.to().size() != 1 || controlled == null
                    || !all.getFirst().strip().equalsIgnoreCase(controlled.strip())) {
                throw new EmailAccountSessionException(prefix + "_RECIPIENT_NOT_CONTROLLED",
                        "Fora do modo produção, somente o destinatário controlado é permitido.");
            }
            return;
        }
        if (envelope.to().isEmpty() || all.size() > MAX_RECIPIENTS
                || all.stream().anyMatch(a -> !ProviderSupport.isEmail(a))) {
            throw new EmailAccountSessionException(prefix + "_RECIPIENT_INVALID",
                    "Os destinatários do cadastro são inválidos para envio.");
        }
    }

    static String messageId(DispatchOperationMode mode, UUID itemId, String fingerprint) {
        var fp = fingerprint == null ? "" : fingerprint;
        return "<folhas." + mode.name().toLowerCase(Locale.ROOT) + "." + ProviderSupport.guidN(itemId) + "."
                + fp.substring(0, Math.min(32, fp.length())) + "@folhas.invalid>";
    }

    private URI api(String relative) {
        return options.apiBaseAddress().resolve(relative);
    }

    private URI upload(String relative) {
        return options.uploadBaseAddress().resolve(relative);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }

    private static HttpRequest.Builder json(URI uri, Object body) {
        return HttpRequest.newBuilder(uri).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.writeBytes(body)));
    }

    private static EmailProviderResult accepted(String messageId, String draftId) {
        return new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, messageId, draftId, null, null);
    }

    private static EmailProviderResult draftConfirmed(String draftId) {
        return new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, null, draftId,
                "GMAIL_DRAFT_CONFIRMED_NOT_SENT",
                "O Gmail confirmou que a mensagem permanece como rascunho; o envio exige nova ação humana explícita.");
    }

    private static EmailProviderResult authenticationFailure(EmailAccountSessionException e, String draftId) {
        return "AUTH_TEMPORARILY_UNAVAILABLE".equals(e.code())
                ? ProviderSupport.transientFailure(e.code(), e.getMessage(), draftId)
                : ProviderSupport.permanent(e.code(), e.getMessage(), draftId);
    }

    static EmailProviderResult mapFailure(ProviderHttp.ApiException e, boolean mayHaveCompleted, String draftId) {
        var status = e.status();
        if (status == 401) {
            return ProviderSupport.permanent("AUTH_REVOKED", "A autorização Google foi rejeitada ou revogada.", draftId);
        }
        if (status == 403) {
            return ProviderSupport.permanent("PROVIDER_REJECTED", "O Gmail recusou a permissão para esta operação.",
                    draftId);
        }
        if (status == 429) {
            var message = e.retryAfter().map(d -> "O Gmail solicitou aguardar " + d.toSeconds() + " segundo(s).")
                    .orElse("O Gmail limitou a operação temporariamente.");
            return ProviderSupport.transientFailure("PROVIDER_THROTTLED", message, draftId);
        }
        if (mayHaveCompleted && ProviderHttp.isTransient(status)) {
            return ambiguous("GMAIL_RESULT_UNKNOWN", draftId);
        }
        if (status == 400 || status == 413 || status == 415 || status == 422 || status == 409) {
            return ProviderSupport.permanent("PROVIDER_REJECTED",
                    "O Gmail rejeitou o conteúdo ou encontrou uma duplicidade.", draftId);
        }
        return mayHaveCompleted ? ambiguous("GMAIL_RESULT_UNKNOWN", draftId)
                : ProviderSupport.permanent("PROVIDER_REJECTED", "O Gmail rejeitou a operação.", draftId);
    }

    private static EmailProviderResult ambiguous(String code, String draftId) {
        return ProviderSupport.ambiguous(code,
                "O resultado do Gmail permanece desconhecido; não tente reenviar sem reconciliação.", draftId);
    }

    /** MimeMessage que preserva o Message-ID determinístico no {@code saveChanges()}. */
    private static final class FixedIdMimeMessage extends MimeMessage {
        private final String id;

        FixedIdMimeMessage(Session session, String id) {
            super(session);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws MessagingException {
            setHeader("Message-ID", id);
        }
    }
}
