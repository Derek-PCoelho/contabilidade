package br.com.contadoresassociados.folhas.domain.clients;

import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.common.Guards;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agregado cliente (PF ou PJ) com identificadores, estabelecimentos, destinatários e sócios.
 *
 * <p>{@link #apply} é <b>atômico</b> (pendência 1.2): o novo estado é montado sobre cópias,
 * validado por completo e só então substitui o estado atual. Se qualquer regra falhar, o
 * agregado continua exatamente como estava.
 */
public final class Client {

    private final UUID id;
    private final UUID organizationId;
    private final UUID createdBy;
    private final Instant createdAtUtc;
    private State state;
    private UUID updatedBy;
    private Instant updatedAtUtc;
    private long version;

    /** Estado mutável substituído de uma vez só. */
    private record State(
            PersonType personType,
            String legalNameOrFullName,
            String preferredName,
            String internalCode,
            String primaryTaxIdNormalized,
            boolean active,
            UUID defaultSubjectTemplateId,
            UUID defaultBodyTemplateId,
            String notes,
            List<ClientIdentifier> identifiers,
            List<Establishment> establishments,
            List<Recipient> recipients,
            List<ClientPartner> partners) {

        static State empty() {
            return new State(null, "", null, null, "", false, null, null, null,
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    public Client(UUID id, UUID organizationId, ClientCatalogDraft draft, UUID actorId, Instant now) {
        Guards.requireIds("client.empty_ids", "Cliente, organização e ator precisam de identificadores.",
                id, organizationId, actorId);
        this.id = id;
        this.organizationId = organizationId;
        this.createdBy = actorId;
        this.createdAtUtc = now;
        this.state = State.empty();
        this.version = 0;
        this.state = build(draft, true);
        this.updatedBy = actorId;
        this.updatedAtUtc = now;
        this.version = 1;
    }

    private Client(UUID id, UUID organizationId, UUID createdBy, Instant createdAtUtc) {
        this.id = id;
        this.organizationId = organizationId;
        this.createdBy = createdBy;
        this.createdAtUtc = createdAtUtc;
        this.state = State.empty();
    }

    /**
     * Reconstrói um cliente persistido. O rascunho deve conter os ids originais de cada filho;
     * a validação completa roda de novo, garantindo que dados corrompidos não entrem em memória.
     */
    public static Client restore(UUID id, UUID organizationId, ClientCatalogDraft draft, long version,
            UUID createdBy, Instant createdAtUtc, UUID updatedBy, Instant updatedAtUtc) {
        var client = new Client(id, organizationId, createdBy, createdAtUtc);
        client.state = client.build(draft, true);
        client.version = version;
        client.updatedBy = updatedBy;
        client.updatedAtUtc = updatedAtUtc;
        return client;
    }

    public void apply(ClientCatalogDraft draft, long expectedVersion, UUID actorId, Instant now) {
        Objects.requireNonNull(draft, "draft");
        if (expectedVersion != version) {
            throw new ConcurrencyConflictException(id, expectedVersion, version);
        }
        if (Guards.isEmpty(actorId)) {
            throw new DomainValidationException("client.empty_actor", "A operação precisa de um ator identificado.");
        }
        var next = build(draft, false);
        state = next;
        updatedBy = actorId;
        updatedAtUtc = now;
        version++;
    }

    private State build(ClientCatalogDraft draft, boolean initializing) {
        Objects.requireNonNull(draft, "draft");
        if (draft.personType() == null) {
            throw new DomainValidationException("client.person_type_required", "Informe se o cliente é PF ou PJ.");
        }
        var personType = draft.personType();
        var legalName = Guards.required(draft.legalNameOrFullName(), 200, "client.legal_name",
                "A razão social ou o nome completo");
        var preferred = Guards.optional(draft.preferredName(), 160, "client.preferred_name", "O nome de exibição");
        var code = Guards.optional(draft.internalCode(), 80, "client.internal_code", "O código interno");
        var taxId = personType == PersonType.LEGAL_ENTITY
                ? BrazilianRegistration.normalizeCnpj(draft.primaryTaxId())
                : BrazilianRegistration.normalizeCpf(draft.primaryTaxId());
        var notes = Guards.optional(draft.notes(), 2000, "client.notes", "As observações");

        var identifiers = reconcile(state.identifiers(), draft.identifiers(), ClientIdentifierDraft::id,
                ClientIdentifier::id, ClientIdentifier::copy, ClientIdentifier::apply, ClientIdentifier::deactivate,
                (newId, d) -> new ClientIdentifier(newId, id, organizationId, d), "identificador", initializing);
        ensureDistinct(identifiers.stream().filter(ClientIdentifier::active)
                        .map(i -> i.type() + ":" + i.valueNormalized()).toList(),
                "client.duplicate_identifier", "Há identificadores ativos repetidos neste cliente.");

        if (personType == PersonType.INDIVIDUAL && !draft.establishments().isEmpty()) {
            throw new DomainValidationException("client.individual_establishment",
                    "Pessoa física não pode ter estabelecimentos.");
        }
        var establishments = reconcile(state.establishments(), draft.establishments(), EstablishmentDraft::id,
                Establishment::id, Establishment::copy, (e, d) -> e.apply(d, taxId), Establishment::deactivate,
                (newId, d) -> new Establishment(newId, id, organizationId, d, taxId), "estabelecimento", initializing);
        ensureDistinct(establishments.stream().filter(Establishment::active).map(Establishment::cnpjNormalized)
                .toList(), "client.duplicate_establishment", "Há estabelecimentos ativos com o mesmo CNPJ.");
        if (establishments.stream().filter(e -> e.active() && e.headOffice()).count() > 1) {
            throw new DomainValidationException("client.multiple_head_offices",
                    "O cliente só pode ter uma matriz ativa.");
        }

        var recipients = reconcile(state.recipients(), draft.recipients(), RecipientDraft::id, Recipient::id,
                Recipient::copy, Recipient::apply, Recipient::deactivate,
                (newId, d) -> new Recipient(newId, id, organizationId, d), "destinatário", initializing);
        ensureDistinct(recipients.stream().filter(Recipient::active).map(Recipient::routeKey).toList(),
                "client.duplicate_recipient_route", "Há destinatários ativos repetidos (mesmo e-mail, papel e escopo).");

        if (personType == PersonType.INDIVIDUAL && draft.partners().stream().anyMatch(ClientPartnerDraft::active)) {
            throw new DomainValidationException("client.individual_partner", "Pessoa física não pode ter sócios.");
        }
        var partners = reconcile(state.partners(), draft.partners(), ClientPartnerDraft::id, ClientPartner::id,
                ClientPartner::copy, ClientPartner::apply, ClientPartner::deactivate,
                (newId, d) -> new ClientPartner(newId, id, organizationId, d), "sócio", initializing);
        ensureDistinct(partners.stream().filter(p -> p.active() && p.cpfNormalized() != null)
                .map(ClientPartner::cpfNormalized).toList(),
                "client.duplicate_partner_cpf", "Há sócios ativos com o mesmo CPF.");

        validateConsistency(personType, identifiers, establishments, recipients);

        return new State(personType, legalName, preferred, code == null ? null : code.toUpperCase(Locale.ROOT),
                taxId, draft.active(), Guards.normalizeOptionalId(draft.defaultSubjectTemplateId()),
                Guards.normalizeOptionalId(draft.defaultBodyTemplateId()), notes,
                List.copyOf(identifiers), List.copyOf(establishments), List.copyOf(recipients), List.copyOf(partners));
    }

    private static void validateConsistency(PersonType type, List<ClientIdentifier> identifiers,
            List<Establishment> establishments, List<Recipient> recipients) {
        if (type == PersonType.LEGAL_ENTITY
                && identifiers.stream().anyMatch(i -> i.active() && i.type() == ClientIdentifierType.CPF)) {
            throw new DomainValidationException("client.legal_entity_cpf",
                    "Pessoa jurídica não pode usar CPF como identificador. Cadastre a pessoa como sócio.");
        }
        if (type == PersonType.INDIVIDUAL && identifiers.stream().anyMatch(i -> i.active()
                && (i.type() == ClientIdentifierType.CNPJ || i.type() == ClientIdentifierType.CNPJ_ROOT))) {
            throw new DomainValidationException("client.individual_cnpj",
                    "Pessoa física não pode usar CNPJ como identificador.");
        }
        var activeEstablishments = establishments.stream().filter(Establishment::active).map(Establishment::id)
                .collect(Collectors.toSet());
        if (recipients.stream().anyMatch(r -> r.active() && r.establishmentId() != null
                && !activeEstablishments.contains(r.establishmentId()))) {
            throw new DomainValidationException("client.recipient_unknown_establishment",
                    "Um destinatário ativo aponta para um estabelecimento inexistente ou inativo.");
        }
        // Pendência 1.5: no máximo um destinatário principal ativo por papel e escopo.
        var primaryKeys = recipients.stream().filter(r -> r.active() && r.primary())
                .map(r -> r.deliveryRole() + ":" + r.establishmentId() + ":" + r.documentTypeId()).toList();
        ensureDistinct(primaryKeys, "client.multiple_primary_recipients",
                "Só pode haver um destinatário principal por papel, estabelecimento e tipo de documento.");
    }

    @FunctionalInterface
    private interface Applier<E, D> {
        void apply(E entity, D draft);
    }

    @FunctionalInterface
    private interface Factory<E, D> {
        E create(UUID id, D draft);
    }

    private static <E, D> List<E> reconcile(List<E> current, List<D> drafts, Function<D, UUID> draftId,
            Function<E, UUID> entityId, Function<E, E> copier, Applier<E, D> applier,
            java.util.function.Consumer<E> deactivator, Factory<E, D> factory, String label, boolean initializing) {
        Objects.requireNonNull(drafts, "drafts");
        var known = current.stream().map(entityId).collect(Collectors.toSet());
        var seen = new HashSet<UUID>();
        for (var draft : drafts) {
            var did = draftId.apply(draft);
            if (!Guards.isEmpty(did) && !seen.add(did)) {
                throw new DomainValidationException("client.duplicate_child_id",
                        "A atualização de " + label + " repete o mesmo id.");
            }
            if (!initializing && !Guards.isEmpty(did) && !known.contains(did)) {
                throw new DomainValidationException("client.unknown_child_id",
                        "A atualização de " + label + " contém um id desconhecido.");
            }
        }
        var result = new ArrayList<E>(current.size() + drafts.size());
        for (var existing : current) {
            var copy = copier.apply(existing);
            var match = drafts.stream().filter(d -> Objects.equals(draftId.apply(d), entityId.apply(existing)))
                    .findFirst();
            if (match.isPresent()) {
                applier.apply(copy, match.get());
            } else {
                deactivator.accept(copy);
            }
            result.add(copy);
        }
        for (var draft : drafts) {
            var did = draftId.apply(draft);
            if (Guards.isEmpty(did)) {
                result.add(factory.create(UUID.randomUUID(), draft));
            } else if (initializing && !known.contains(did)) {
                result.add(factory.create(did, draft));
            }
        }
        return result;
    }

    private static void ensureDistinct(List<String> values, String code, String message) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new DomainValidationException(code, message);
        }
    }

    public UUID id() { return id; }
    public UUID organizationId() { return organizationId; }
    public PersonType personType() { return state.personType(); }
    public String legalNameOrFullName() { return state.legalNameOrFullName(); }
    public String preferredName() { return state.preferredName(); }
    public String displayName() {
        return state.preferredName() == null ? state.legalNameOrFullName() : state.preferredName();
    }
    public String internalCode() { return state.internalCode(); }
    public String primaryTaxIdNormalized() { return state.primaryTaxIdNormalized(); }
    public boolean active() { return state.active(); }
    public UUID defaultSubjectTemplateId() { return state.defaultSubjectTemplateId(); }
    public UUID defaultBodyTemplateId() { return state.defaultBodyTemplateId(); }
    public String notes() { return state.notes(); }
    public UUID createdBy() { return createdBy; }
    public UUID updatedBy() { return updatedBy; }
    public Instant createdAtUtc() { return createdAtUtc; }
    public Instant updatedAtUtc() { return updatedAtUtc; }
    public long version() { return version; }
    public List<ClientIdentifier> identifiers() { return Collections.unmodifiableList(state.identifiers()); }
    public List<Establishment> establishments() { return Collections.unmodifiableList(state.establishments()); }
    public List<Recipient> recipients() { return Collections.unmodifiableList(state.recipients()); }
    public List<ClientPartner> partners() { return Collections.unmodifiableList(state.partners()); }

    /** Converte o estado atual num rascunho (ida e volta para edição e persistência). */
    public ClientCatalogDraft toDraft() {
        return new ClientCatalogDraft(personType(), legalNameOrFullName(), preferredName(), internalCode(),
                primaryTaxIdNormalized(), active(), defaultSubjectTemplateId(), defaultBodyTemplateId(), notes(),
                state.identifiers().stream().map(i -> new ClientIdentifierDraft(i.id(), i.type(), i.valueNormalized(),
                        i.semanticRole(), i.priority(), i.active(), i.uniqueWithinOrganization())).toList(),
                state.establishments().stream().map(e -> new EstablishmentDraft(e.id(), e.cnpjNormalized(),
                        e.legalName(), e.displayName(), e.internalCode(), e.headOffice(), e.active())).toList(),
                state.recipients().stream().map(r -> new RecipientDraft(r.id(), r.establishmentId(), r.displayName(),
                        r.emailNormalized(), r.deliveryRole(), r.documentTypeId(), r.primary(), r.active(),
                        r.validFrom(), r.validTo())).toList(),
                state.partners().stream().map(p -> new ClientPartnerDraft(p.id(), p.fullName(), p.cpfNormalized(),
                        p.role(), p.active(), p.emailNormalized())).toList());
    }
}
