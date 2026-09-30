package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Modelo de mensagem (assunto + corpo) global ou de um cliente. */
public final class MessageTemplate {

    private final UUID id;
    private final UUID organizationId;
    private UUID clientId;
    private UUID documentTypeId;
    private String name;
    private String subjectTemplate;
    private String bodyTemplate;
    private SignatureMode signatureMode;
    private boolean isDefault;
    private boolean active;
    private long version;
    private UUID updatedBy;
    private Instant updatedAtUtc;

    /**
     * Validador de placeholders injetado pela camada de contratos (pendência 8.15: placeholders
     * inválidos passam a ser recusados já no salvamento, e não só na composição).
     */
    public MessageTemplate(UUID id, UUID organizationId, MessageTemplateDraft draft, UUID actorId, Instant now,
            Consumer<String> placeholderValidator) {
        Guards.requireIds("template.empty_ids", "Modelo, organização e ator precisam de identificadores.",
                id, organizationId, actorId);
        this.id = id;
        this.organizationId = organizationId;
        applyInternal(draft, actorId, now, placeholderValidator);
        this.version = 1;
    }

    public static MessageTemplate restore(UUID id, UUID organizationId, MessageTemplateDraft draft, long version,
            UUID updatedBy, Instant updatedAtUtc) {
        var template = new MessageTemplate(id, organizationId, draft, updatedBy, updatedAtUtc, value -> { });
        template.version = version;
        return template;
    }

    public void apply(MessageTemplateDraft draft, long expectedVersion, UUID actorId, Instant now,
            Consumer<String> placeholderValidator) {
        if (expectedVersion != version) {
            throw new ConcurrencyConflictException(id, expectedVersion, version);
        }
        applyInternal(draft, actorId, now, placeholderValidator);
        version++;
    }

    private void applyInternal(MessageTemplateDraft draft, UUID actorId, Instant now, Consumer<String> validator) {
        Objects.requireNonNull(draft, "draft");
        if (Guards.isEmpty(actorId)) {
            throw new DomainValidationException("template.empty_actor", "A operação precisa de um ator identificado.");
        }
        var n = Guards.required(draft.name(), 160, "template.name", "O nome do modelo");
        var subject = Guards.required(draft.subjectTemplate(), 500, "template.subject", "O assunto");
        var body = Guards.required(draft.bodyTemplate(), 20_000, "template.body", "O corpo");
        if (subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0) {
            throw new DomainValidationException("template.subject_line_break",
                    "O assunto não pode ter quebras de linha.");
        }
        validator.accept(subject);
        validator.accept(body);
        clientId = Guards.normalizeOptionalId(draft.clientId());
        documentTypeId = Guards.normalizeOptionalId(draft.documentTypeId());
        name = n;
        subjectTemplate = subject;
        bodyTemplate = body;
        signatureMode = draft.signatureMode() == null ? SignatureMode.NONE : draft.signatureMode();
        isDefault = draft.isDefault() && draft.active();
        active = draft.active();
        updatedBy = actorId;
        updatedAtUtc = now;
    }

    public MessageTemplateDraft toDraft() {
        return new MessageTemplateDraft(clientId, documentTypeId, name, subjectTemplate, bodyTemplate, signatureMode,
                isDefault, active);
    }

    public boolean isGlobal() { return clientId == null; }
    public UUID id() { return id; }
    public UUID organizationId() { return organizationId; }
    public UUID clientId() { return clientId; }
    public UUID documentTypeId() { return documentTypeId; }
    public String name() { return name; }
    public String subjectTemplate() { return subjectTemplate; }
    public String bodyTemplate() { return bodyTemplate; }
    public SignatureMode signatureMode() { return signatureMode; }
    public boolean isDefault() { return isDefault; }
    public boolean active() { return active; }
    public long version() { return version; }
    public UUID updatedBy() { return updatedBy; }
    public Instant updatedAtUtc() { return updatedAtUtc; }
}
