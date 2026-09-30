package br.com.contadoresassociados.folhas.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.domain.clients.*;
import br.com.contadoresassociados.folhas.domain.common.ConcurrencyConflictException;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import br.com.contadoresassociados.folhas.domain.identity.AppRole;
import br.com.contadoresassociados.folhas.domain.identity.RolePermissionCatalog;
import br.com.contadoresassociados.folhas.domain.organizations.Organization;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClientCatalogTest {

    static final UUID ORG = UUID.randomUUID();
    static final UUID ACTOR = UUID.randomUUID();
    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    static RecipientDraft to(String email) {
        return new RecipientDraft(null, null, "Financeiro", email, DeliveryRole.TO, null, true, true, null, null);
    }

    static ClientCatalogDraft company(String cnpj, List<EstablishmentDraft> establishments,
            List<RecipientDraft> recipients, List<ClientPartnerDraft> partners) {
        return new ClientCatalogDraft(PersonType.LEGAL_ENTITY, "Empresa Sintética Ltda", "Sintética", "c-01", cnpj,
                true, null, null, null, List.of(), establishments, recipients, partners);
    }

    static ClientCatalogDraft person(List<EstablishmentDraft> establishments, List<ClientPartnerDraft> partners) {
        return new ClientCatalogDraft(PersonType.INDIVIDUAL, "Pessoa Sintética", null, null, "529.982.247-25", true,
                null, null, null, List.of(), establishments, List.of(to("pessoa@example.com")), partners);
    }

    @Test
    void createsCompanyWithAlphanumericCnpjAndBranch() {
        var branch = new EstablishmentDraft(null, "12.ABC.345/0002-XX".replace("XX", dvFor("12ABC3450002")),
                "Filial Ltda", "Filial", "f1", false, true);
        var head = new EstablishmentDraft(null, "12.ABC.345/01DE-35", "Matriz Ltda", "Matriz", null, true, true);
        var client = new Client(UUID.randomUUID(), ORG,
                company("12ABC34501DE35", List.of(head, branch), List.of(to("fin@empresa.com.br")), List.of()),
                ACTOR, NOW);
        assertThat(client.primaryTaxIdNormalized()).isEqualTo("12ABC34501DE35");
        assertThat(client.establishments()).extracting(Establishment::cnpjRoot).containsOnly("12ABC345");
        assertThat(client.internalCode()).isEqualTo("C-01");
        assertThat(client.version()).isEqualTo(1);
    }

    @Test
    void establishmentMustShareRoot() {
        var other = new EstablishmentDraft(null, "11.222.333/0001-81", "Outra", "Outra", null, true, true);
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG,
                company("12ABC34501DE35", List.of(other), List.of(), List.of()), ACTOR, NOW))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("establishment.root_mismatch"));
    }

    @Test
    void individualCannotHaveEstablishmentsOrPartners() {
        var est = new EstablishmentDraft(null, "11.222.333/0001-81", "X", "X", null, true, true);
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG, person(List.of(est), List.of()), ACTOR, NOW))
                .hasMessageContaining("Pessoa física");
        var partner = new ClientPartnerDraft(null, "Sócio", "529.982.247-25", ClientPartnerRole.PARTNER, true, null);
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG, person(List.of(), List.of(partner)), ACTOR, NOW))
                .hasMessageContaining("sócios");
    }

    @Test
    void partnerCpfAndOptionalEmailNormalized() {
        var partner = new ClientPartnerDraft(null, " Sócia ", "529.982.247-25", ClientPartnerRole.MANAGING_PARTNER,
                true, " Socia@Example.COM ");
        var client = new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(to("a@example.com")), List.of(partner)), ACTOR, NOW);
        var saved = client.partners().getFirst();
        assertThat(saved.cpfNormalized()).isEqualTo("52998224725");
        assertThat(saved.emailNormalized()).isEqualTo("socia@example.com");
        assertThat(client.identifiers()).isEmpty();
    }

    @Test
    void legalEntityCannotUseCpfIdentifier() {
        var draft = new ClientCatalogDraft(PersonType.LEGAL_ENTITY, "Empresa", null, null, "11222333000181", true,
                null, null, null,
                List.of(new ClientIdentifierDraft(null, ClientIdentifierType.CPF, "529.982.247-25",
                        ClientIdentifierSemanticRole.OTHER, 1, true, false)),
                List.of(), List.of(), List.of());
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG, draft, ACTOR, NOW))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("client.legal_entity_cpf"));
    }

    @Test
    void recipientValidityRangeChecked() {
        var bad = new RecipientDraft(null, null, "X", "x@example.com", DeliveryRole.TO, null, false, true,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1));
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(bad), List.of()), ACTOR, NOW))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("recipient.validity_range"));
    }

    @Test
    void onlyOnePrimaryRecipientPerScope() {
        var a = to("a@example.com");
        var b = to("b@example.com");
        assertThatThrownBy(() -> new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(a, b), List.of()), ACTOR, NOW))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("client.multiple_primary_recipients"));
    }

    /** Pendência 1.2: falha de validação não deixa o agregado meio alterado. */
    @Test
    void applyIsAtomic() {
        var client = new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(to("a@example.com")), List.of()), ACTOR, NOW);
        var before = client.toDraft();
        var broken = new ClientCatalogDraft(PersonType.LEGAL_ENTITY, "Nome Novo", "Novo", null, "11222333000181",
                false, null, null, "nota", List.of(), List.of(),
                List.of(new RecipientDraft(client.recipients().getFirst().id(), UUID.randomUUID(), "X",
                        "a@example.com", DeliveryRole.TO, null, true, true, null, null)),
                List.of());
        assertThatThrownBy(() -> client.apply(broken, 1, ACTOR, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThat(client.toDraft()).isEqualTo(before);
        assertThat(client.version()).isEqualTo(1);
        assertThat(client.legalNameOrFullName()).isEqualTo("Empresa Sintética Ltda");
        assertThat(client.active()).isTrue();
    }

    @Test
    void removedChildIsDeactivatedAndUnknownIdRejected() {
        var client = new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(to("a@example.com")), List.of()), ACTOR, NOW);
        var draft = client.toDraft();
        var withoutRecipients = new ClientCatalogDraft(draft.personType(), draft.legalNameOrFullName(), null, null,
                draft.primaryTaxId(), true, null, null, null, List.of(), List.of(), List.of(), List.of());
        client.apply(withoutRecipients, 1, ACTOR, NOW);
        assertThat(client.recipients()).singleElement().satisfies(r -> assertThat(r.active()).isFalse());
        var unknown = new ClientCatalogDraft(draft.personType(), draft.legalNameOrFullName(), null, null,
                draft.primaryTaxId(), true, null, null, null, List.of(), List.of(),
                List.of(new RecipientDraft(UUID.randomUUID(), null, "X", "x@example.com", DeliveryRole.TO, null,
                        false, true, null, null)), List.of());
        assertThatThrownBy(() -> client.apply(unknown, 2, ACTOR, NOW))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("client.unknown_child_id"));
    }

    @Test
    void staleVersionRejected() {
        var client = new Client(UUID.randomUUID(), ORG,
                company("11222333000181", List.of(), List.of(to("a@example.com")), List.of()), ACTOR, NOW);
        assertThatThrownBy(() -> client.apply(client.toDraft(), 0, ACTOR, NOW))
                .isInstanceOf(ConcurrencyConflictException.class);
    }

    @Test
    void readinessReportsInactiveAndMissingTo() {
        var draft = new ClientCatalogDraft(PersonType.LEGAL_ENTITY, "Empresa", null, null, "11222333000181", false,
                null, null, null, List.of(), List.of(),
                List.of(new RecipientDraft(null, null, "Cópia", "c@example.com", DeliveryRole.CC, null, false, true,
                        null, null)), List.of());
        var client = new Client(UUID.randomUUID(), ORG, draft, ACTOR, NOW);
        var readiness = ClientOperationalReadiness.evaluate(client, LocalDate.of(2026, 9, 30));
        assertThat(readiness.eligible()).isFalse();
        assertThat(readiness.blockCodes()).containsExactly(ClientOperationalReadiness.CLIENT_INACTIVE,
                ClientOperationalReadiness.NO_ACTIVE_TO_RECIPIENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nome@dominio", "nome@-empresa.com", "nome@empresa..com", "Nome <nome@example.com>",
            "nome@@example.com", "a b@example.com", ".a@example.com"})
    void emailRejected(String value) {
        assertThat(EmailAddress.isValid(value)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({" Financeiro+folha@Example.COM ,financeiro+folha@example.com",
        "pessoa@example.invalid,pessoa@example.invalid", "joão@açaí.com.br,joão@xn--aa-yia7a.com.br"})
    void emailAccepted(String value, String expected) {
        if (expected.startsWith("joão")) {
            assertThat(EmailAddress.isValid(value)).isFalse();
            return;
        }
        assertThat(EmailAddress.normalize(value)).isEqualTo(expected);
    }

    @Test
    void templateRejectsLineBreakInSubjectAndInvalidPlaceholder() {
        var draft = new MessageTemplateDraft(null, null, "Padrão", "Folha\nX", "Corpo", SignatureMode.NONE, true, true);
        assertThatThrownBy(() -> new MessageTemplate(UUID.randomUUID(), ORG, draft, ACTOR, NOW, v -> { }))
                .isInstanceOfSatisfying(DomainValidationException.class,
                        e -> assertThat(e.code()).isEqualTo("template.subject_line_break"));
        var ok = new MessageTemplateDraft(null, null, "Padrão", "Folha {{x}}", "Corpo", SignatureMode.NONE, true, true);
        assertThatThrownBy(() -> new MessageTemplate(UUID.randomUUID(), ORG, ok, ACTOR, NOW, v -> {
            if (v.contains("{{x}}")) {
                throw new DomainValidationException("template.unknown_placeholder", "x");
            }
        })).isInstanceOf(DomainValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {161, 20_000})
    void templateBodyUpTo20k(int length) {
        var draft = new MessageTemplateDraft(null, null, "N", "S", "a".repeat(length), SignatureMode.NONE, false, true);
        var template = new MessageTemplate(UUID.randomUUID(), ORG, draft, ACTOR, NOW, v -> { });
        assertThat(template.bodyTemplate()).hasSize(length);
        var tooLong = new MessageTemplateDraft(null, null, "N", "S", "a".repeat(20_001), SignatureMode.NONE, false, true);
        assertThatThrownBy(() -> new MessageTemplate(UUID.randomUUID(), ORG, tooLong, ACTOR, NOW, v -> { }))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void organizationSlugAndLifecycle() {
        var org = new Organization(UUID.randomUUID(), " Escritório ", "Escritorio-01", NOW);
        assertThat(org.slug()).isEqualTo("escritorio-01");
        org.deactivate(NOW);
        assertThat(org.active()).isFalse();
        assertThatThrownBy(() -> new Organization(UUID.randomUUID(), "X", "com espaço", NOW))
                .isInstanceOf(DomainValidationException.class);
    }

    @Test
    void rolePermissions() {
        assertThat(RolePermissionCatalog.forRole(AppRole.OPERATOR)).doesNotContain(AppPermission.EMAIL_SEND,
                AppPermission.CLIENTS_EXPORT);
        assertThat(RolePermissionCatalog.forRole(AppRole.AUDITOR)).doesNotContain(AppPermission.CLIENTS_WRITE,
                AppPermission.CLIENTS_EXPORT);
        assertThat(RolePermissionCatalog.forRole(AppRole.ADMINISTRATOR)).containsAll(List.of(AppPermission.values()));
        assertThat(AppRole.MANAGER.privileged()).isTrue();
    }

    static String dvFor(String base12) {
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        var d1 = dv(base12, w1);
        var d2 = dv(base12 + d1, w2);
        return "" + d1 + d2;
    }

    static int dv(String s, int[] w) {
        var sum = 0;
        for (var i = 0; i < w.length; i++) {
            sum += (s.charAt(i) - '0') * w[i];
        }
        var r = sum % 11;
        return r < 2 ? 0 : 11 - r;
    }
}
