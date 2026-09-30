package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ClientIdentifier {

    private final UUID id;
    private final UUID clientId;
    private final UUID organizationId;
    private ClientIdentifierType type;
    private String valueNormalized;
    private ClientIdentifierSemanticRole semanticRole;
    private int priority;
    private boolean active;
    private boolean uniqueWithinOrganization;

    ClientIdentifier(UUID id, UUID clientId, UUID organizationId, ClientIdentifierDraft draft) {
        this.id = id;
        this.clientId = clientId;
        this.organizationId = organizationId;
        apply(draft);
    }

    private ClientIdentifier(ClientIdentifier other) {
        this.id = other.id;
        this.clientId = other.clientId;
        this.organizationId = other.organizationId;
        this.type = other.type;
        this.valueNormalized = other.valueNormalized;
        this.semanticRole = other.semanticRole;
        this.priority = other.priority;
        this.active = other.active;
        this.uniqueWithinOrganization = other.uniqueWithinOrganization;
    }

    ClientIdentifier copy() {
        return new ClientIdentifier(this);
    }

    void apply(ClientIdentifierDraft draft) {
        type = draft.type();
        valueNormalized = normalize(draft.type(), draft.value());
        semanticRole = draft.semanticRole() == null ? ClientIdentifierSemanticRole.OTHER : draft.semanticRole();
        priority = Math.clamp(draft.priority(), 0, 1000);
        active = draft.active();
        uniqueWithinOrganization = draft.uniqueWithinOrganization();
    }

    void deactivate() {
        active = false;
    }

    public static String normalize(ClientIdentifierType type, String value) {
        var normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new DomainValidationException("identifier.invalid_length",
                    "O identificador deve ter entre 1 e 200 caracteres.");
        }
        return switch (type) {
            case CNPJ -> BrazilianRegistration.normalizeCnpj(normalized);
            case CNPJ_ROOT -> BrazilianRegistration.normalizeCnpjRoot(normalized);
            case CPF -> BrazilianRegistration.normalizeCpf(normalized);
            case LEGAL_NAME_ALIAS -> normalizeText(normalized);
            case INTERNAL_CODE, OTHER -> normalized.toUpperCase(Locale.ROOT);
        };
    }

    static String normalizeText(String value) {
        var composed = Normalizer.normalize(value, Normalizer.Form.NFC).toUpperCase(Locale.ROOT);
        return Arrays.stream(composed.split("\\s+")).filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public UUID organizationId() { return organizationId; }
    public ClientIdentifierType type() { return type; }
    public String valueNormalized() { return valueNormalized; }
    public ClientIdentifierSemanticRole semanticRole() { return semanticRole; }
    public int priority() { return priority; }
    public boolean active() { return active; }
    public boolean uniqueWithinOrganization() { return uniqueWithinOrganization; }
}
