package br.com.contadoresassociados.folhas.infrastructure.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.clients.CatalogException;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.ClientResolver;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientCatalogImportRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierSemanticRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.clients.SignatureModeModel;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionMethod;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.infrastructure.documents.SqliteLocalClientResolver;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.CatalogRecords;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalCatalogAndResolverTest {

    static final String CNPJ_A = "11222333000181";
    static final String CNPJ_A_FILIAL = "11222333000262";
    static final String CNPJ_B = "99888777000100";
    static final String CNPJ_ALFA = "12ABC34501DE35";
    static final String CPF = "52998224725";

    LocalDatabase db;
    SqliteLocalClientCatalogService catalog;
    AtomicInteger revalidations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        db = LocalDatabase.inMemory();
        catalog = new SqliteLocalClientCatalogService(db, Clock.fixed(Instant.parse("2026-09-30T15:00:00Z")),
                revalidations::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    static RecipientModel to(String email) {
        return new RecipientModel(UUID.randomUUID(), null, "Financeiro", email, DeliveryRoleModel.TO, null, true, true,
                null, null);
    }

    static ClientMutationRequest company(String name, String cnpj, String code, List<EstablishmentModel> establishments) {
        return new ClientMutationRequest(0, PersonTypeModel.LEGAL_ENTITY, name, null, code, cnpj, true, null, null, null,
                List.of(), establishments, List.of(to("financeiro@" + code.toLowerCase() + ".com.br")), List.of());
    }

    static ClientResolutionRequest fields(RecognizedField... fields) {
        return ClientResolver.minimize(List.of(fields));
    }

    static RecognizedField field(SemanticFieldRole role, String value) {
        return new RecognizedField(role.name(), value, value, role, BigDecimal.ONE, null);
    }

    @Test
    void saveVersioningUniquenessAndDotNetCompatibleStorage() {
        var saved = catalog.save(null, company("Padaria Pão Quente Ltda", "11.222.333/0001-81", "c01", List.of()));
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.primaryTaxId()).isEqualTo(CNPJ_A);
        assertThat(saved.internalCode()).isEqualTo("C01");
        assertThat(revalidations).hasValue(1);

        var raw = db.read(c -> CatalogRecords.all(c, "local-client", com.fasterxml.jackson.databind.JsonNode.class))
                .getFirst();
        assertThat(raw.get("legalNameOrFullName").asText()).isEqualTo("Padaria Pão Quente Ltda");
        assertThat(raw.get("personType").asInt()).isEqualTo(1);
        assertThat(raw.get("isActive").asBoolean()).isTrue();

        assertThatThrownBy(() -> catalog.save(null, company("Outra", CNPJ_A_FILIAL, "c02", List.of())))
                .isInstanceOf(CatalogException.Duplicate.class);
        assertThatThrownBy(() -> catalog.save(null, company("Outra", CNPJ_B, "C01", List.of())))
                .isInstanceOf(CatalogException.Duplicate.class);

        var update = new ClientMutationRequest(1, PersonTypeModel.LEGAL_ENTITY, "Padaria Pão Quente", "Pão Quente", "C01",
                CNPJ_A, true, null, null, null, List.of(), List.of(), saved.recipients(), List.of());
        var updated = catalog.save(saved.id(), update);
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> catalog.save(saved.id(), update)).isInstanceOf(CatalogException.Concurrency.class);
        assertThat(catalog.audit(saved.id())).extracting(a -> a.action()).containsExactlyInAnyOrder("created", "updated");
    }

    @Test
    void failedRevalidationRollsBackTheCatalogChange() {
        var failing = new SqliteLocalClientCatalogService(db, Clock.system(), () -> {
            throw new IllegalStateException("revisão falhou");
        });
        assertThatThrownBy(() -> failing.save(null, company("X Ltda", CNPJ_A, "X1", List.of())))
                .hasMessageContaining("revisão falhou");
        assertThat(catalog.search(null, null, null, 0, 50).total()).isZero();
    }

    @Test
    void searchIgnoresAccentsPagesAndReadinessUsesDomain() {
        catalog.save(null, company("Açougue São João", CNPJ_A, "A1", List.of()));
        catalog.save(null, company("Mercado Bom Preço", CNPJ_B, "B1", List.of()));
        assertThat(catalog.search("sao joao", null, null, 0, 50).items()).singleElement()
                .satisfies(i -> assertThat(i.primaryTaxIdMasked()).endsWith("81"));
        assertThat(catalog.search("99.888", null, null, 0, 50).total()).isEqualTo(1);
        var page = catalog.search(null, true, null, 1, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);

        var id = catalog.search("mercado", null, null, 0, 10).items().getFirst().id();
        assertThat(catalog.readiness(id)).hasValueSatisfying(r -> assertThat(r.isEligible()).isTrue());
        assertThat(catalog.getMany(List.of(id, UUID.randomUUID(), id))).hasSize(1);
    }

    @Test
    void archiveRequiresInactiveAndMovesRecord() {
        var saved = catalog.save(null, company("Arquivável Ltda", CNPJ_A, "Z1", List.of()));
        assertThatThrownBy(() -> catalog.archiveClient(saved.id(), new ArchiveRequest(1, null)))
                .hasMessageContaining("Inative o cliente");
        var inactive = catalog.setClientActive(saved.id(), 1, false);
        assertThat(inactive.version()).isEqualTo(2);
        catalog.archiveClient(saved.id(), new ArchiveRequest(2, "encerrado"));
        assertThat(catalog.get(saved.id())).isEmpty();
        boolean archived = db.read(c -> CatalogRecords.exists(c, "local-client-archived", saved.id()));
        assertThat(archived).isTrue();
    }

    @Test
    void templatesValidatePlaceholdersUniquenessAndDefaults() {
        var client = catalog.save(null, company("Modelo Ltda", CNPJ_A, "M1", List.of()));
        var request = new MessageTemplateMutationRequest(0, client.id(), null, "Padrão", "Folha {{periodo.rotulo}}",
                "Olá {{cliente.razao_social}}", SignatureModeModel.ORGANIZATION, true, true);
        var template = catalog.saveTemplate(null, request);
        assertThat(template.version()).isEqualTo(1);
        assertThatThrownBy(() -> catalog.saveTemplate(null, request)).isInstanceOf(CatalogException.Duplicate.class);

        var withDefault = new ClientMutationRequest(1, PersonTypeModel.LEGAL_ENTITY, "Modelo Ltda", null, "M1", CNPJ_A,
                true, template.id(), template.id(), null, List.of(), List.of(), client.recipients(), List.of());
        catalog.save(client.id(), withDefault);
        assertThatThrownBy(() -> catalog.setTemplateActive(template.id(), 1, false))
                .hasMessageContaining("definida como padrão");
        assertThat(catalog.templates(client.id(), false)).hasSize(1);
    }

    @Test
    void importDryRunAndRealImport() {
        catalog.save(null, company("Origem Ltda", CNPJ_A, "O1", List.of()));
        var document = catalog.export();
        var target = LocalDatabase.inMemory();
        try {
            var other = new SqliteLocalClientCatalogService(target, Clock.system(), null);
            var dry = other.importCatalog(new ClientCatalogImportRequest(true, false, document));
            assertThat(dry.clientsCreated()).isEqualTo(1);
            assertThat(other.search(null, null, null, 0, 10).total()).isZero();
            other.importCatalog(new ClientCatalogImportRequest(false, false, document));
            assertThat(other.search(null, null, null, 0, 10).total()).isEqualTo(1);
            var again = other.importCatalog(new ClientCatalogImportRequest(false, false, document));
            assertThat(again.warnings()).hasSize(1);
        } finally {
            target.close();
        }
    }

    @Test
    void readsLegacyRecordWithInvalidEmailAndQuarantinesMisplacedCpf() {
        var id = UUID.randomUUID();
        var legacy = new ClientDetails(id, PersonTypeModel.LEGAL_ENTITY, "Legado Ltda", null, "L1", CNPJ_A, true, null, null,
                null, 3, java.time.OffsetDateTime.parse("2025-01-01T00:00Z"), java.time.OffsetDateTime.parse("2025-01-01T00:00Z"),
                List.of(new ClientIdentifierModel(UUID.randomUUID(), ClientIdentifierTypeModel.CPF, CPF,
                        ClientIdentifierSemanticRoleModel.OTHER, 1, true, false)),
                List.of(), List.of(new RecipientModel(UUID.randomUUID(), null, "Antigo", "email invalido",
                        DeliveryRoleModel.TO, null, true, true, null, null)), List.of());
        db.transaction(c -> {
            CatalogRecords.upsert(c, "local-client", id, 3, legacy.updatedAtUtc(), legacy);
            return null;
        });
        assertThat(catalog.readiness(id)).hasValueSatisfying(r -> assertThat(r.blockCodes())
                .contains("RECIPIENT_EMAIL_INVALID", "NO_ACTIVE_TO_RECIPIENT"));
        var reactivated = catalog.setClientActive(id, 3, false);
        assertThat(reactivated.identifiers().getFirst().isActive()).isFalse();
    }

    @Test
    void resolverFollowsDotNetPrecedenceIncludingAlphanumericCnpj() {
        var head = new EstablishmentModel(UUID.randomUUID(), CNPJ_A, "Matriz", "Matriz", null, true, true);
        var branch = new EstablishmentModel(UUID.randomUUID(), CNPJ_A_FILIAL, "Filial", "Filial", null, false, true);
        var a = catalog.save(null, company("Transportes Rápidos Ltda", CNPJ_A, "T1", List.of(head, branch)));
        var alfa = catalog.save(null, company("Nova Era Tecnologia SA", CNPJ_ALFA, "N1", List.of()));
        var person = catalog.save(null, new ClientMutationRequest(0, PersonTypeModel.INDIVIDUAL, "Maria da Silva", null,
                "P1", CPF, true, null, null, null, List.of(), List.of(), List.of(to("maria@x.com.br")), List.of()));
        var resolver = new SqliteLocalClientResolver(db);

        var byCnpj = resolver.resolve(fields(field(SemanticFieldRole.EMPLOYER_TAX_ID, "11.222.333/0001-81")));
        assertThat(byCnpj.clientId()).isEqualTo(a.id());
        assertThat(byCnpj.method()).isEqualTo(ClientResolutionMethod.EXACT_CLIENT_TAX_ID);
        assertThat(byCnpj.clientTaxIdMasked()).isEqualTo("11.***.***/****-81");

        var byBranch = resolver.resolve(fields(field(SemanticFieldRole.ESTABLISHMENT_TAX_ID, CNPJ_A_FILIAL)));
        assertThat(byBranch.establishmentId()).isEqualTo(branch.id());
        assertThat(byBranch.method()).isEqualTo(ClientResolutionMethod.EXACT_ESTABLISHMENT_TAX_ID);

        var byAlfa = resolver.resolve(fields(field(SemanticFieldRole.EMPLOYER_TAX_ID, "12.ABC.345/01DE-35")));
        assertThat(byAlfa.clientId()).isEqualTo(alfa.id());

        var byCpf = resolver.resolve(fields(field(SemanticFieldRole.CLIENT_TAX_ID, "529.982.247-25")));
        assertThat(byCpf.clientId()).isEqualTo(person.id());
        assertThat(byCpf.clientTaxIdMasked()).isEqualTo("***.982.***-25");

        var byRoot = resolver.resolve(fields(field(SemanticFieldRole.EMPLOYER_TAX_ID, "11222333999952"),
                field(SemanticFieldRole.EMPLOYER_NAME, "TRANSPORTES RAPIDOS")));
        assertThat(byRoot.method()).isEqualTo(ClientResolutionMethod.UNIQUE_CNPJ_ROOT_AND_NAME);

        var byCode = resolver.resolve(fields(field(SemanticFieldRole.INTERNAL_CODE, "n1")));
        assertThat(byCode.clientId()).isEqualTo(alfa.id());

        var fuzzy = resolver.resolve(fields(field(SemanticFieldRole.CLIENT_NAME, "Transportes Rapidos Ltd")));
        assertThat(fuzzy.clientId()).isNull();
        assertThat(fuzzy.alternatives()).isNotEmpty();
        assertThat(fuzzy.blockers()).containsExactly("client.not_resolved");

        catalog.setClientActive(alfa.id(), alfa.version(), false);
        var inactive = resolver.resolve(fields(field(SemanticFieldRole.EMPLOYER_TAX_ID, CNPJ_ALFA)));
        assertThat(inactive.blockers()).containsExactly("client.inactive");

        var batch = resolver.resolveBatch(List.of(fields(field(SemanticFieldRole.EMPLOYER_TAX_ID, CNPJ_A)),
                fields(field(SemanticFieldRole.CLIENT_TAX_ID, CPF))));
        assertThat(batch).extracting(r -> r.clientId()).containsExactly(a.id(), person.id());
        assertThat(Json.write(byCnpj)).doesNotContain(CNPJ_A);
    }
}
