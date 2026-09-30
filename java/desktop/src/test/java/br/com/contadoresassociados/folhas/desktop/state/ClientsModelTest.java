package br.com.contadoresassociados.folhas.desktop.state;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import br.com.contadoresassociados.folhas.infrastructure.clients.SqliteLocalClientCatalogService;
import br.com.contadoresassociados.folhas.infrastructure.persistence.local.LocalDatabase;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fluxo da tela de Clientes contra o catálogo local real (SQLite em memória). */
class ClientsModelTest {

    private static final String CNPJ = "11.222.333/0001-81";
    private LocalDatabase db;
    private ClientCatalogService catalog;
    private final List<String> statuses = new ArrayList<>();
    private ClientsModel model;

    @BeforeEach
    void setUp() {
        db = LocalDatabase.inMemory();
        catalog = new SqliteLocalClientCatalogService(db, Clock.system(), null);
        model = new ClientsModel(catalog, UiTasks.synchronous(), statuses::add, null);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void fillCompany() {
        model.newClient();
        model.legalName.set("Loja Primavera Ltda");
        model.primaryTaxId.set(CNPJ);
        model.newRecipientName.set("Financeiro");
        model.newRecipientEmail.set("Financeiro@Primavera.com.br");
        model.addRecipient();
    }

    @Test
    void createsCompanyAndShowsItInTheList() {
        fillCompany();
        assertThat(model.recipients).singleElement().satisfies(r -> {
            assertThat(r.email()).isEqualTo("financeiro@primavera.com.br");
            assertThat(r.isPrimary()).isTrue();
        });

        model.saveClient();

        assertThat(model.feedbackIsError.get()).as(model.feedbackMessage.get()).isFalse();
        assertThat(model.feedbackMessage.get()).contains("salvo com sucesso");
        assertThat(model.existingClient.get()).isTrue();
        assertThat(model.clientSaveActionLabel().get()).isEqualTo("Atualizar cadastro");
        assertThat(model.readinessMessage.get()).isEqualTo("Cadastro pronto para receber documentos");
        assertThat(model.clients).singleElement().satisfies(row -> {
            assertThat(row.displayName()).isEqualTo("Loja Primavera Ltda");
            assertThat(row.taxIdDisplay()).doesNotContain("222.333");
        });
        model.maskClientTaxIds.set(false);
        assertThat(model.clients.getFirst().taxIdDisplay()).isEqualTo(CNPJ);
    }

    @Test
    void validatesEssentialDataWithDotNetMessages() {
        model.newClient();
        model.saveClient();
        assertThat(model.feedbackMessage.get()).isEqualTo("Informe a razão social da empresa.");

        model.legalName.set("Empresa");
        model.primaryTaxId.set("11.222.333/0001-00");
        model.saveClient();
        assertThat(model.feedbackMessage.get()).startsWith("O CNPJ tem 14 dígitos, mas os dois dígitos verificadores não conferem");

        model.personType.set(PersonTypeModel.INDIVIDUAL);
        model.primaryTaxId.set("123");
        model.saveClient();
        assertThat(model.feedbackMessage.get()).startsWith("Informe o CPF completo com 11 dígitos");
        assertThat(model.feedbackIsError.get()).isTrue();
    }

    @Test
    void savingDuplicateTaxIdReopensExistingInsteadOfDuplicating() {
        fillCompany();
        model.saveClient();
        var firstId = model.currentClientId();

        fillCompany();
        model.legalName.set("Outro nome");
        model.saveClient();

        assertThat(model.currentClientId()).isEqualTo(firstId);
        assertThat(model.legalName.get()).isEqualTo("Loja Primavera Ltda");
        assertThat(model.feedbackMessage.get()).contains("já estava cadastrado");
        assertThat(catalog.search("", null, null, 0, 50).total()).isEqualTo(1);
    }

    @Test
    void inactivateThenArchiveRequiresConfirmation() {
        fillCompany();
        model.saveClient();

        model.archiveClient();
        assertThat(model.feedbackMessage.get()).startsWith("Inative o cliente antes de excluí-lo");

        model.toggleClientActive();
        assertThat(model.clientActive.get()).isFalse();
        assertThat(model.includeInactive.get()).isTrue();
        assertThat(model.feedbackMessage.get()).contains("inativado. O histórico foi preservado.");

        model.archiveClient();
        assertThat(model.clientArchiveConfirmation.get()).isTrue();
        assertThat(model.clientArchiveActionLabel().get()).isEqualTo("Confirmar exclusão da lista");

        model.archiveClient();
        assertThat(model.feedbackMessage.get()).contains("excluído da lista");
        assertThat(model.existingClient.get()).isFalse();
        assertThat(model.clients).isEmpty();
    }

    @Test
    void partnerLinkedToDeliveryCreatesRecipientAndValidatesCpf() {
        model.newClient();
        model.newPartnerName.set("Ana Souza");
        model.newPartnerCpf.set("111.444.777-00");
        model.addPartner();
        assertThat(model.partnerInputError.get()).isNotBlank();
        assertThat(model.partners).isEmpty();

        model.newPartnerCpf.set("111.444.777-35");
        model.usePartnerAsDeliveryContact.set(true);
        model.addPartner();
        assertThat(model.partnerInputError.get()).isEqualTo("Informe o e-mail do representante para usá-lo também como contato de entrega.");

        model.newPartnerEmail.set("ana@primavera.com.br");
        model.addPartner();
        assertThat(model.partners).singleElement().satisfies(p -> assertThat(p.cpf()).isEqualTo("111.444.777-35"));
        assertThat(model.recipients).singleElement().satisfies(r -> assertThat(r.email()).isEqualTo("ana@primavera.com.br"));
        assertThat(model.newPartnerName.get()).isEmpty();
    }

    @Test
    void templateLifecycleWithPlaceholdersAndArchive() {
        fillCompany();
        model.saveClient();

        model.applyStandardTemplate(PersonTypeModel.LEGAL_ENTITY);
        assertThat(model.templateName.get()).isNotBlank();
        model.templateBody.set("Olá");
        model.insertPlaceholder(MessageTemplatePlaceholderCatalog.CONTACT_NAME);
        assertThat(model.templateBody.get()).isEqualTo("Olá {{contato.nome}}");
        model.templateSubject.set("Documentos {{inexistente}}");
        model.saveTemplate();
        assertThat(model.feedbackMessage.get()).startsWith("Há informações variáveis desconhecidas: inexistente");

        model.templateSubject.set("Documentos de {{periodo.rotulo}}");
        model.saveTemplate();
        assertThat(model.feedbackMessage.get()).isEqualTo("Mensagem personalizada criada com sucesso. Nenhum e-mail foi enviado.");
        assertThat(model.templates).singleElement().satisfies(t -> assertThat(t.isDefault()).isTrue());

        model.selectedTemplate.set(model.templates.getFirst());
        model.archiveTemplate();
        assertThat(model.feedbackMessage.get()).isEqualTo("Inative a mensagem antes de excluí-la da lista.");
        model.toggleTemplateActive();
        assertThat(model.templates.getFirst().isActive()).isFalse();
        model.archiveTemplate();
        model.archiveTemplate();
        assertThat(model.templates).isEmpty();
    }

    @Test
    void listLoadsTaxIdsInOneBatch() {
        for (var cnpj : List.of("11.222.333/0001-81", "45.723.174/0001-10", "19.131.243/0001-97")) {
            model.newClient();
            model.legalName.set("Cliente " + cnpj);
            model.primaryTaxId.set(cnpj);
            model.saveClient();
            assertThat(model.feedbackIsError.get()).as(model.feedbackMessage.get()).isFalse();
        }
        var getCalls = new AtomicInteger();
        var batchCalls = new AtomicInteger();
        var counting = (ClientCatalogService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ClientCatalogService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("get")) {
                        getCalls.incrementAndGet();
                    }
                    if (method.getName().equals("getMany")) {
                        batchCalls.incrementAndGet();
                    }
                    return method.invoke(catalog, args);
                });
        var fresh = new ClientsModel(counting, UiTasks.synchronous(), statuses::add, null);
        fresh.loadClients();

        assertThat(fresh.clients).hasSize(3);
        assertThat(getCalls).hasValue(0);
        assertThat(batchCalls).hasValue(1);
        assertThat(statuses.getLast()).isEqualTo("3 cliente(s) encontrado(s). Selecione um cadastro para abrir e editar.");
        assertThat(fresh.loadedTaxIdKeys()).hasSize(3);
    }

    @Test
    void establishmentCnpjIsValidatedOnAdd() {
        model.newClient();
        model.newEstablishmentCnpj.set("11.222.333/0002-00");
        model.newEstablishmentName.set("Filial Centro");
        model.addEstablishment();
        assertThat(model.establishments).isEmpty();
        assertThat(model.feedbackIsError.get()).isTrue();

        model.newEstablishmentCnpj.set("11.222.333/0002-62");
        model.addEstablishment();
        assertThat(model.establishments).singleElement().satisfies(e -> assertThat(e.isHeadOffice()).isTrue());
    }

}
