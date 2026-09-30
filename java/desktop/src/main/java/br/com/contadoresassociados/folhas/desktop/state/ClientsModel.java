package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.clients.ClientPartnerInputValidator;
import br.com.contadoresassociados.folhas.contracts.clients.ArchiveRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierSemanticRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientReadinessResponse;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.clients.SignatureModeModel;
import br.com.contadoresassociados.folhas.contracts.clients.StandardMessageTemplatePresets;
import br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.ClientOperationalReadiness;
import br.com.contadoresassociados.folhas.domain.clients.EmailAddress;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Estado e regras da tela de Clientes — porte fiel do trecho de cadastro do
 * {@code MainViewModel} (.NET), com as correções:
 * <ul>
 *   <li>6.13: CPF/CNPJ da lista carregados em lote ({@code getMany}), sem N+1;</li>
 *   <li>6.x: toda chamada de serviço roda fora da thread da interface ({@link UiTasks});</li>
 *   <li>4.19/6.12: inativar/arquivar clientes e modelos nos perfis Local e Conectado.</li>
 * </ul>
 */
public final class ClientsModel {

    /** Linha da lista de clientes (equivalente a {@code ClientListRow}). */
    public record ClientRow(ClientListItem source, String primaryTaxId, boolean maskTaxId) {
        public UUID id() {
            return source.id();
        }

        public String displayName() {
            return source.displayName();
        }

        public boolean isActive() {
            return source.isActive();
        }

        public String taxIdDisplay() {
            if (!maskTaxId && primaryTaxId != null && !primaryTaxId.isBlank()) {
                var digits = primaryTaxId.replaceAll("\\D", "");
                if (source.personType() == PersonTypeModel.INDIVIDUAL && digits.length() == 11) {
                    return BrazilianRegistration.formatCpf(digits);
                }
                if (source.personType() == PersonTypeModel.LEGAL_ENTITY && digits.length() == 14) {
                    return BrazilianRegistration.formatCnpj(digits);
                }
                return primaryTaxId;
            }
            return source.primaryTaxIdMasked();
        }
    }

    public record PlaceholderTarget(String key, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    public static final List<PersonTypeModel> PERSON_TYPES = List.of(PersonTypeModel.values());
    public static final List<ClientIdentifierTypeModel> IDENTIFIER_TYPES = List.of(
            ClientIdentifierTypeModel.LEGAL_NAME_ALIAS, ClientIdentifierTypeModel.INTERNAL_CODE,
            ClientIdentifierTypeModel.OTHER);
    public static final List<DeliveryRoleModel> DELIVERY_ROLES = List.of(DeliveryRoleModel.values());
    public static final List<ClientPartnerRoleModel> PARTNER_ROLES = List.of(ClientPartnerRoleModel.values());
    public static final List<PlaceholderTarget> PLACEHOLDER_TARGETS = List.of(
            new PlaceholderTarget("body", "Texto do e-mail"), new PlaceholderTarget("subject", "Assunto do e-mail"));

    private final ClientCatalogService catalog;
    private final UiTasks tasks;
    private final Consumer<String> status;
    private final Runnable catalogChanged;

    // ---- lista
    public final StringProperty searchText = new SimpleStringProperty("");
    public final BooleanProperty includeInactive = new SimpleBooleanProperty(false);
    public final BooleanProperty maskClientTaxIds = new SimpleBooleanProperty(true);
    public final ObservableList<ClientRow> clients = FXCollections.observableArrayList();
    public final ObjectProperty<ClientRow> selectedClient = new SimpleObjectProperty<>();
    public final BooleanProperty clientListPanelExpanded = new SimpleBooleanProperty(true);
    public final BooleanProperty clientEditorPanelExpanded = new SimpleBooleanProperty(true);
    private final Map<UUID, String> clientTaxIds = new HashMap<>();

    // ---- editor
    public final ObjectProperty<PersonTypeModel> personType = new SimpleObjectProperty<>(PersonTypeModel.LEGAL_ENTITY);
    public final StringProperty legalName = new SimpleStringProperty("");
    public final StringProperty preferredName = new SimpleStringProperty("");
    public final StringProperty internalCode = new SimpleStringProperty("");
    public final StringProperty primaryTaxId = new SimpleStringProperty("");
    public final StringProperty notes = new SimpleStringProperty("");
    public final BooleanProperty clientActive = new SimpleBooleanProperty(true);
    public final StringProperty readinessMessage = new SimpleStringProperty("Novo cadastro — preencha os dados essenciais");
    public final BooleanProperty existingClient = new SimpleBooleanProperty(false);
    public final BooleanProperty saving = new SimpleBooleanProperty(false);
    public final BooleanProperty clientArchiveConfirmation = new SimpleBooleanProperty(false);
    public final StringProperty feedbackMessage = new SimpleStringProperty("");
    public final BooleanProperty feedbackIsError = new SimpleBooleanProperty(false);
    private UUID currentClientId;
    private long currentVersion;
    private UUID defaultSubjectTemplateId;
    private UUID defaultBodyTemplateId;

    // ---- coleções do cadastro
    public final ObservableList<ClientIdentifierModel> identifiers = FXCollections.observableArrayList();
    public final ObservableList<EstablishmentModel> establishments = FXCollections.observableArrayList();
    public final ObservableList<RecipientModel> recipients = FXCollections.observableArrayList();
    public final ObservableList<ClientPartnerModel> partners = FXCollections.observableArrayList();

    // ---- rascunhos
    public final StringProperty newPartnerName = new SimpleStringProperty("");
    public final StringProperty newPartnerCpf = new SimpleStringProperty("");
    public final StringProperty newPartnerEmail = new SimpleStringProperty("");
    public final ObjectProperty<ClientPartnerRoleModel> partnerRole = new SimpleObjectProperty<>(ClientPartnerRoleModel.MANAGING_PARTNER);
    public final BooleanProperty usePartnerAsDeliveryContact = new SimpleBooleanProperty(false);
    public final StringProperty partnerInputError = new SimpleStringProperty("");
    public final ObjectProperty<ClientPartnerModel> selectedPartner = new SimpleObjectProperty<>();
    public final StringProperty newRecipientName = new SimpleStringProperty("");
    public final StringProperty newRecipientEmail = new SimpleStringProperty("");
    public final ObjectProperty<DeliveryRoleModel> deliveryRole = new SimpleObjectProperty<>(DeliveryRoleModel.TO);
    public final ObjectProperty<RecipientModel> selectedRecipient = new SimpleObjectProperty<>();
    public final StringProperty newEstablishmentCnpj = new SimpleStringProperty("");
    public final StringProperty newEstablishmentName = new SimpleStringProperty("");
    public final ObjectProperty<EstablishmentModel> selectedEstablishment = new SimpleObjectProperty<>();
    public final ObjectProperty<ClientIdentifierTypeModel> identifierType = new SimpleObjectProperty<>(ClientIdentifierTypeModel.LEGAL_NAME_ALIAS);
    public final StringProperty newIdentifierValue = new SimpleStringProperty("");
    public final ObjectProperty<ClientIdentifierModel> selectedIdentifier = new SimpleObjectProperty<>();

    // ---- mensagens personalizadas
    public final ObservableList<MessageTemplateModel> templates = FXCollections.observableArrayList();
    public final ObjectProperty<MessageTemplateModel> selectedTemplate = new SimpleObjectProperty<>();
    public final StringProperty templateName = new SimpleStringProperty("");
    public final StringProperty templateSubject = new SimpleStringProperty("");
    public final StringProperty templateBody = new SimpleStringProperty("");
    public final ObjectProperty<PlaceholderTarget> placeholderTarget = new SimpleObjectProperty<>(PLACEHOLDER_TARGETS.getFirst());
    public final BooleanProperty editingTemplate = new SimpleBooleanProperty(false);
    public final BooleanProperty templateArchiveConfirmation = new SimpleBooleanProperty(false);
    private UUID editingTemplateId;
    private long editingTemplateVersion;
    private UUID editingTemplateDocumentTypeId;
    private SignatureModeModel editingTemplateSignatureMode = SignatureModeModel.ORGANIZATION;
    private boolean editingTemplateIsDefault;
    private boolean editingTemplateIsActive = true;

    public ClientsModel(ClientCatalogService catalog, UiTasks tasks, Consumer<String> status, Runnable catalogChanged) {
        this.catalog = catalog;
        this.tasks = tasks;
        this.status = status;
        this.catalogChanged = catalogChanged == null ? () -> { } : catalogChanged;
        selectedClient.addListener((obs, old, now) -> clientArchiveConfirmation.set(false));
        selectedTemplate.addListener((obs, old, now) -> templateArchiveConfirmation.set(false));
        maskClientTaxIds.addListener((obs, old, now) -> clients.setAll(clients.stream()
                .map(row -> new ClientRow(row.source(), row.primaryTaxId(), now)).toList()));
    }

    // ================================================================ rótulos derivados

    public BooleanBinding isLegalEntity() {
        return Bindings.equal(personType, PersonTypeModel.LEGAL_ENTITY);
    }

    public BooleanBinding hasFeedback() {
        return Bindings.createBooleanBinding(() -> !feedbackMessage.get().isBlank(), feedbackMessage);
    }

    public BooleanBinding hasPartnerInputError() {
        return Bindings.createBooleanBinding(() -> !partnerInputError.get().isBlank(), partnerInputError);
    }

    public StringBinding clientEditorTitle() {
        return Bindings.createStringBinding(() -> legalName.get().isBlank() ? "Novo cliente" : legalName.get(), legalName);
    }

    public StringBinding clientSaveActionLabel() {
        return Bindings.when(existingClient).then("Atualizar cadastro").otherwise("Cadastrar cliente");
    }

    public StringBinding clientStatusLabel() {
        return Bindings.when(clientActive).then("Ativo").otherwise("Inativo");
    }

    public StringBinding clientStatusActionLabel() {
        return Bindings.when(clientActive).then("Inativar agora").otherwise("Reativar agora");
    }

    public StringBinding clientArchiveActionLabel() {
        return Bindings.when(clientArchiveConfirmation).then("Confirmar exclusão da lista").otherwise("Excluir cadastro da lista");
    }

    public StringBinding clientListPanelActionLabel() {
        return Bindings.when(clientListPanelExpanded).then("Recolher lista").otherwise("Mostrar lista");
    }

    public StringBinding clientEditorPanelActionLabel() {
        return Bindings.when(clientEditorPanelExpanded).then("Recolher cadastro").otherwise("Mostrar cadastro");
    }

    public StringBinding primaryTaxIdLabel() {
        return Bindings.when(isLegalEntity()).then("CNPJ").otherwise("CPF");
    }

    public StringBinding primaryTaxIdPlaceholder() {
        return Bindings.when(isLegalEntity()).then("00.000.000/0001-00").otherwise("000.000.000-00");
    }

    public StringBinding primaryTaxIdHelp() {
        return Bindings.when(isLegalEntity())
                .then("Pode digitar com ou sem pontuação. A validação confere os dígitos oficiais do CNPJ, sem consultar cadastro externo; o aplicativo também reconhece a raiz presente nos documentos.")
                .otherwise("Pode digitar com ou sem pontos e hífen. A validação confere os dígitos oficiais do CPF, sem consultar cadastro externo.");
    }

    public StringBinding legalNameLabel() {
        return Bindings.when(isLegalEntity()).then("Razão social").otherwise("Nome completo");
    }

    public StringBinding templateEditorTitle() {
        return Bindings.when(editingTemplate).then("Editar mensagem personalizada").otherwise("Nova mensagem personalizada");
    }

    public StringBinding templateSaveActionLabel() {
        return Bindings.when(editingTemplate).then("Atualizar mensagem").otherwise("Criar mensagem");
    }

    public StringBinding templateStatusActionLabel() {
        return Bindings.createStringBinding(() -> selectedTemplate.get() != null && selectedTemplate.get().isActive()
                ? "Inativar mensagem" : "Reativar mensagem", selectedTemplate);
    }

    public StringBinding templateArchiveActionLabel() {
        return Bindings.when(templateArchiveConfirmation).then("Confirmar exclusão").otherwise("Excluir mensagem");
    }

    public BooleanBinding hasSelectedTemplate() {
        return selectedTemplate.isNotNull();
    }

    // ================================================================ lista

    public void toggleClientListPanel() {
        clientListPanelExpanded.set(!clientListPanelExpanded.get());
    }

    public void toggleClientEditorPanel() {
        clientEditorPanelExpanded.set(!clientEditorPanelExpanded.get());
    }

    public void loadClients() {
        tasks.run(() -> searchRows(includeInactive.get()), rows -> {
            clients.setAll(rows);
            status.accept(rows.isEmpty()
                    ? "Nenhum cliente corresponde à busca. Confira o nome, CPF/CNPJ ou marque “Mostrar inativos”."
                    : rows.size() + " cliente(s) encontrado(s). Selecione um cadastro para abrir e editar.");
        }, this::fail);
    }

    /** Busca e completa os CPF/CNPJ com uma única consulta em lote (6.13). */
    private List<ClientRow> searchRows(boolean withInactive) {
        var result = catalog.search(searchText.get(), withInactive ? null : Boolean.TRUE, null, 0, 200);
        var missing = result.items().stream().map(ClientListItem::id).filter(id -> !clientTaxIds.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            for (var details : catalog.getMany(missing)) {
                clientTaxIds.put(details.id(), details.primaryTaxId());
            }
        }
        var mask = maskClientTaxIds.get();
        return result.items().stream().map(item -> new ClientRow(item, clientTaxIds.get(item.id()), mask)).toList();
    }

    private void refreshList(UUID selectId) {
        tasks.run(() -> searchRows(includeInactive.get()), rows -> {
            clients.setAll(rows);
            if (selectId != null) {
                clients.stream().filter(r -> r.id().equals(selectId)).findFirst().ifPresent(selectedClient::set);
            }
        }, this::fail);
    }

    /** Abre o cliente selecionado, exceto quando ele já é o cadastro em edição. */
    public void openSelectedClientIfDifferent() {
        var row = selectedClient.get();
        if (row != null && !row.id().equals(currentClientId)) {
            openSelectedClient();
        }
    }

    /**
     * Abre um cadastro a partir de outra tela (ex.: documento com cliente inativo), incluindo
     * inativos na lista — equivalente a {@code OpenInactiveClientFromDocumentAsync}.
     */
    public void openClient(UUID clientId) {
        includeInactive.set(true);
        searchText.set("");
        tasks.run(() -> new Opened(loadEditorData(clientId), searchRows(true)), result -> {
            var data = result.data();
            clients.setAll(result.rows());
            if (data == null) {
                feedback("O cliente selecionado não foi encontrado. Atualize a lista e tente novamente.", true);
                status.accept(feedbackMessage.get());
                return;
            }
            applyEditorData(data);
            clients.stream().filter(r -> r.id().equals(clientId)).findFirst().ifPresent(selectedClient::set);
            focusEditor();
            status.accept(clientActive.get()
                    ? "Cadastro aberto. Confira CPF/CNPJ, nomes e identificadores; depois atualize o cadastro."
                    : "Cadastro inativo aberto. Clique em “Reativar agora”; os documentos serão analisados novamente automaticamente.");
        }, this::fail);
    }

    public void openSelectedClient() {
        var row = selectedClient.get();
        if (row == null) {
            feedback("Selecione um cliente na lista antes de abrir o cadastro.", true);
            return;
        }
        tasks.run(() -> loadEditorData(row.id()), data -> {
            if (data == null) {
                feedback("O cliente selecionado não foi encontrado. Atualize a lista e tente novamente.", true);
                status.accept(feedbackMessage.get());
                return;
            }
            applyEditorData(data);
            focusEditor();
        }, this::fail);
    }

    private record EditorData(ClientDetails details, ClientReadinessResponse readiness, List<MessageTemplateModel> templates) {
    }

    private record Opened(EditorData data, List<ClientRow> rows) {
    }

    private EditorData loadEditorData(UUID clientId) {
        var details = catalog.get(clientId).orElse(null);
        if (details == null) {
            return null;
        }
        return new EditorData(details, catalog.readiness(clientId).orElse(null), catalog.templates(clientId, true));
    }

    private void applyEditorData(EditorData data) {
        var details = data.details();
        apply(details);
        var readiness = data.readiness();
        var blocks = readiness == null ? List.<String>of() : readiness.blockCodes();
        readinessMessage.set(!details.isActive()
                ? "Cadastro inativo — reative para reconhecer e liberar documentos"
                : readiness == null ? "Situação operacional ainda não conferida"
                : readiness.isEligible() ? "Cadastro pronto para receber documentos"
                : blocks.contains(ClientOperationalReadiness.RECIPIENT_EMAIL_INVALID) ? "Corrija o e-mail de entrega incompleto antes de continuar"
                : blocks.contains(ClientOperationalReadiness.PARTNER_EMAIL_INVALID) ? "Corrija o e-mail do sócio ou representante antes de continuar"
                : blocks.contains(ClientOperationalReadiness.NO_ACTIVE_TO_RECIPIENT) ? "Adicione um e-mail principal para a entrega dos documentos"
                : "Cadastro precisa de atenção antes de receber documentos");
        templates.setAll(data.templates());
        resetTemplateEditor(true);
        var name = displayName(details);
        var message = details.isActive()
                ? "Cadastro de “" + name + "” aberto para edição."
                : "Cadastro inativo de “" + name + "” recuperado. Você pode reativá-lo sem recadastrar o CPF/CNPJ.";
        feedback(message, false);
        status.accept(message);
    }

    private void apply(ClientDetails details) {
        clientTaxIds.put(details.id(), details.primaryTaxId());
        currentClientId = details.id();
        currentVersion = details.version();
        defaultSubjectTemplateId = details.defaultSubjectTemplateId();
        defaultBodyTemplateId = details.defaultBodyTemplateId();
        existingClient.set(true);
        personType.set(details.personType());
        legalName.set(details.legalNameOrFullName());
        preferredName.set(nz(details.preferredName()));
        internalCode.set(nz(details.internalCode()));
        primaryTaxId.set(details.primaryTaxId());
        notes.set(nz(details.notes()));
        clientActive.set(details.isActive());
        identifiers.setAll(details.identifiers());
        establishments.setAll(details.establishments());
        recipients.setAll(details.recipients());
        partners.setAll(details.partners());
    }

    // ================================================================ novo / salvar

    public void newClient() {
        focusEditor();
        currentClientId = null;
        currentVersion = 0;
        defaultSubjectTemplateId = null;
        defaultBodyTemplateId = null;
        existingClient.set(false);
        selectedClient.set(null);
        personType.set(PersonTypeModel.LEGAL_ENTITY);
        legalName.set("");
        preferredName.set("");
        internalCode.set("");
        primaryTaxId.set("");
        notes.set("");
        clientActive.set(true);
        readinessMessage.set("Novo cadastro — preencha os dados essenciais");
        identifiers.clear();
        establishments.clear();
        recipients.clear();
        partners.clear();
        templates.clear();
        resetTemplateEditor(true);
        resetPartnerDraft();
        feedback("Novo cadastro iniciado. Apenas razão social/nome completo e CNPJ/CPF são essenciais para salvar.", false);
        status.accept("Comece pelos dados essenciais e pelo e-mail que receberá os documentos.");
    }

    private void focusEditor() {
        clientEditorPanelExpanded.set(true);
        clientListPanelExpanded.set(false);
    }

    public void saveClient() {
        saveClient(null);
    }

    private void saveClient(Runnable afterSave) {
        var validation = validateClientDraft();
        if (validation != null) {
            feedback(validation, true);
            status.accept(validation);
            return;
        }
        if (currentClientId == null) {
            // Recupera um cadastro já existente com o mesmo CPF/CNPJ em vez de duplicar.
            tasks.run(this::findByPrimaryTaxId, existing -> {
                if (existing != null) {
                    if (!existing.details().isActive()) {
                        includeInactive.set(true);
                    }
                    applyEditorData(existing);
                    refreshList(existing.details().id());
                    var message = existing.details().isActive()
                            ? "Este CPF/CNPJ já estava cadastrado. O cadastro existente foi aberto para você atualizar, sem criar uma duplicidade."
                            : "Este CPF/CNPJ pertence a um cadastro inativo. Ele foi recuperado; use “Reativar agora” para voltar a utilizá-lo.";
                    feedback(message, false);
                    status.accept(message);
                } else {
                    persist(afterSave);
                }
            }, error -> {
                var message = "Não foi possível conferir se este CPF/CNPJ já está cadastrado. Os dados digitados foram mantidos; tente novamente.";
                feedback(message, true);
                status.accept(message);
            });
            return;
        }
        persist(afterSave);
    }

    private EditorData findByPrimaryTaxId() {
        var normalized = personType.get() == PersonTypeModel.LEGAL_ENTITY
                ? BrazilianRegistration.normalizeCnpj(primaryTaxId.get())
                : BrazilianRegistration.normalizeCpf(primaryTaxId.get());
        var result = catalog.search(normalized, null, personType.get(), 0, 50);
        var ids = result.items().stream().map(ClientListItem::id).toList();
        if (ids.isEmpty()) {
            return null;
        }
        return catalog.getMany(ids).stream().filter(d -> normalized.equals(d.primaryTaxId())).findFirst()
                .map(d -> loadEditorData(d.id())).orElse(null);
    }

    private void persist(Runnable afterSave) {
        var wasExisting = currentClientId != null;
        var legal = personType.get() == PersonTypeModel.LEGAL_ENTITY;
        var request = new ClientMutationRequest(currentVersion, personType.get(), legalName.get().trim(),
                emptyToNull(preferredName.get()), emptyToNull(internalCode.get()), primaryTaxId.get(), clientActive.get(),
                defaultSubjectTemplateId, defaultBodyTemplateId, emptyToNull(notes.get()), List.copyOf(identifiers),
                legal ? List.copyOf(establishments) : List.of(), List.copyOf(recipients),
                legal ? List.copyOf(partners) : List.of());
        var clientId = currentClientId;
        saving.set(true);
        feedback("Salvando e conferindo o cadastro…", false);
        tasks.run(() -> {
            var saved = catalog.save(clientId, request);
            return new EditorData(saved, catalog.readiness(saved.id()).orElse(null), catalog.templates(saved.id(), true));
        }, data -> {
            saving.set(false);
            applyEditorData(data);
            var saved = data.details();
            var name = displayName(saved);
            var success = saved.isActive()
                    ? wasExisting ? "Cadastro de “" + name + "” atualizado com sucesso."
                    : "Cliente “" + name + "” salvo com sucesso e disponível na lista."
                    : "Cadastro de “" + name + "” atualizado e mantido como inativo.";
            feedback(success, false);
            status.accept(success);
            refreshList(saved.id());
            catalogChanged.run();
            if (afterSave != null) {
                afterSave.run();
            }
        }, error -> {
            saving.set(false);
            fail(error);
        });
    }

    /** Mesmas mensagens de {@code TryValidateClientDraft}. Retorna {@code null} se válido. */
    public String validateClientDraft() {
        var legal = personType.get() == PersonTypeModel.LEGAL_ENTITY;
        if (legalName.get().isBlank()) {
            return legal ? "Informe a razão social da empresa." : "Informe o nome completo da pessoa.";
        }
        // CNPJ alfanumérico (IN RFB 2.229/2024) conta letras e números; CPF, somente dígitos.
        var positions = primaryTaxId.get().replaceAll(legal ? "[^A-Za-z0-9]" : "\\D", "");
        if (positions.length() != (legal ? 14 : 11)) {
            return legal ? "Informe o CNPJ completo com 14 dígitos. Pode digitar com ou sem pontuação."
                    : "Informe o CPF completo com 11 dígitos. Pode digitar com ou sem pontuação.";
        }
        try {
            if (legal) {
                BrazilianRegistration.normalizeCnpj(primaryTaxId.get());
            } else {
                BrazilianRegistration.normalizeCpf(primaryTaxId.get());
            }
        } catch (DomainValidationException e) {
            return legal
                    ? "O CNPJ tem 14 dígitos, mas os dois dígitos verificadores não conferem. Use um CNPJ válido; números aleatórios são rejeitados para evitar associação ao cliente errado."
                    : "O CPF tem 11 dígitos, mas os dois dígitos verificadores não conferem. Use um CPF válido; números aleatórios são rejeitados para evitar associação à pessoa errada.";
        }
        if (recipients.stream().anyMatch(r -> r.displayName() == null || r.displayName().isBlank() || !EmailAddress.isValid(r.email()))) {
            return "Confira o nome e o e-mail dos contatos de entrega antes de salvar.";
        }
        if (partners.stream().anyMatch(p -> p.email() != null && !p.email().isBlank() && !EmailAddress.isValid(p.email()))) {
            return "Confira o e-mail do sócio ou representante antes de salvar.";
        }
        return null;
    }

    // ================================================================ status / arquivamento

    public void toggleClientActive() {
        clientArchiveConfirmation.set(false);
        if (currentClientId == null) {
            feedback("Salve o novo cliente antes de alterar o status.", true);
            status.accept("Salve o novo cliente antes de alterar o status.");
            return;
        }
        var target = !clientActive.get();
        var clientId = currentClientId;
        var version = currentVersion;
        saving.set(true);
        feedback(target ? "Reativando o cadastro…" : "Inativando o cadastro…", false);
        tasks.run(() -> {
            var updated = catalog.setClientActive(clientId, version, target);
            return new EditorData(updated, catalog.readiness(clientId).orElse(null), catalog.templates(clientId, true));
        }, data -> {
            saving.set(false);
            var updated = data.details();
            if (!updated.isActive()) {
                includeInactive.set(true);
            }
            applyEditorData(data);
            var name = displayName(updated);
            var eligible = data.readiness() != null && data.readiness().isEligible();
            var success = updated.isActive()
                    ? eligible ? "Cliente “" + name + "” reativado e disponível para novos documentos."
                    : "Cliente “" + name + "” reativado. Corrija as pendências indicadas antes de continuar."
                    : "Cliente “" + name + "” inativado. O histórico foi preservado.";
            feedback(success, false);
            status.accept(success);
            refreshList(updated.id());
            catalogChanged.run();
        }, error -> {
            saving.set(false);
            fail(error);
        });
    }

    public void archiveClient() {
        if (currentClientId == null) {
            feedback("A exclusão recuperável está disponível somente para cadastros já salvos.", true);
            status.accept(feedbackMessage.get());
            return;
        }
        if (clientActive.get()) {
            feedback("Inative o cliente antes de excluí-lo da lista. Isso evita interromper documentos ou mensagens em andamento.", true);
            status.accept(feedbackMessage.get());
            return;
        }
        if (!clientArchiveConfirmation.get()) {
            clientArchiveConfirmation.set(true);
            feedback("Clique novamente para confirmar. O cadastro sairá da lista, mas a trilha de auditoria será preservada; clientes com documentos, mensagens ou modelos vinculados não podem ser excluídos.", false);
            status.accept(feedbackMessage.get());
            return;
        }
        var clientId = currentClientId;
        var version = currentVersion;
        var name = preferredName.get().isBlank() ? legalName.get() : preferredName.get();
        tasks.run(() -> catalog.archiveClient(clientId, new ArchiveRequest(version, "Excluído da lista pelo operador.")), () -> {
            clientTaxIds.remove(clientId);
            newClient();
            includeInactive.set(true);
            refreshList(null);
            var success = "Cadastro de “" + name + "” excluído da lista. A auditoria protegida foi mantida.";
            feedback(success, false);
            status.accept(success);
            catalogChanged.run();
        }, error -> {
            clientArchiveConfirmation.set(false);
            fail(error);
        });
    }

    // ================================================================ sócios, contatos, unidades, apelidos

    public void addPartner() {
        partnerInputError.set("");
        if (personType.get() != PersonTypeModel.LEGAL_ENTITY) {
            partnerInputError.set("Sócios e representantes pertencem ao cadastro de empresas.");
            status.accept(partnerInputError.get());
            return;
        }
        if (newPartnerName.get().isBlank()) {
            partnerError("Informe o nome completo do sócio ou representante.");
            return;
        }
        var cpf = ClientPartnerInputValidator.validateOptionalCpf(newPartnerCpf.get());
        if (!cpf.valid()) {
            partnerError(cpf.errorMessage() == null ? "Revise o CPF do sócio ou representante." : cpf.errorMessage());
            status.accept(partnerInputError.get());
            return;
        }
        var email = emptyToNull(newPartnerEmail.get());
        if (email != null && !EmailAddress.isValid(email)) {
            partnerError("O e-mail do sócio ou representante não é válido. Confira o endereço digitado.");
            return;
        }
        email = email == null ? null : EmailAddress.normalize(email);
        if (usePartnerAsDeliveryContact.get() && email == null) {
            partnerError("Informe o e-mail do representante para usá-lo também como contato de entrega.");
            return;
        }
        var name = newPartnerName.get().trim();
        var linked = usePartnerAsDeliveryContact.get();
        partners.add(new ClientPartnerModel(null, name, cpf.formatted(), partnerRole.get(), true, email));
        final var partnerEmail = email;
        if (linked && recipients.stream().noneMatch(r -> r.email().equalsIgnoreCase(partnerEmail))) {
            recipients.add(new RecipientModel(null, null, name, partnerEmail, DeliveryRoleModel.TO, null,
                    recipients.stream().noneMatch(RecipientModel::isPrimary), true, null, null));
        }
        resetPartnerDraft();
        var message = linked
                ? "Representante adicionado e vinculado ao contato de entrega. Salve o cliente para confirmar."
                : "Sócio ou representante adicionado ao cadastro. Salve o cliente para confirmar.";
        feedback(message, false);
        status.accept(message);
    }

    private void partnerError(String message) {
        partnerInputError.set(message);
        feedback(message, true);
    }

    private void resetPartnerDraft() {
        newPartnerName.set("");
        newPartnerCpf.set("");
        newPartnerEmail.set("");
        partnerRole.set(ClientPartnerRoleModel.MANAGING_PARTNER);
        usePartnerAsDeliveryContact.set(false);
        partnerInputError.set("");
    }

    public void removePartner() {
        var partner = selectedPartner.get();
        if (partner != null) {
            partners.remove(partner);
            selectedPartner.set(null);
        }
    }

    public void addRecipient() {
        if (newRecipientName.get().isBlank() || newRecipientEmail.get().isBlank()) {
            feedback("Informe o nome e o e-mail do contato de entrega.", true);
            return;
        }
        if (!EmailAddress.isValid(newRecipientEmail.get())) {
            feedback("O e-mail do contato de entrega não é válido. Confira o endereço digitado.", true);
            return;
        }
        var email = EmailAddress.normalize(newRecipientEmail.get());
        if (recipients.stream().anyMatch(r -> r.email().equalsIgnoreCase(email))) {
            feedback("Este e-mail já está na lista de contatos de entrega.", true);
            return;
        }
        recipients.add(new RecipientModel(null, null, newRecipientName.get().trim(), email, deliveryRole.get(), null,
                recipients.stream().noneMatch(RecipientModel::isPrimary), true, null, null));
        newRecipientName.set("");
        newRecipientEmail.set("");
        feedback("Contato de entrega adicionado. Salve o cliente para confirmar.", false);
    }

    public void removeRecipient() {
        var recipient = selectedRecipient.get();
        if (recipient != null) {
            recipients.remove(recipient);
            selectedRecipient.set(null);
        }
    }

    public void addEstablishment() {
        if (personType.get() != PersonTypeModel.LEGAL_ENTITY || newEstablishmentCnpj.get().isBlank()
                || newEstablishmentName.get().isBlank()) {
            status.accept("Estabelecimento exige cliente PJ, CNPJ e nome.");
            return;
        }
        // Correção: valida o CNPJ da unidade já na inclusão (antes só falhava ao salvar).
        var problem = ClientPartnerInputValidator.cnpjProblem(newEstablishmentCnpj.get());
        if (problem != null) {
            feedback(problem, true);
            return;
        }
        var name = newEstablishmentName.get().trim();
        establishments.add(new EstablishmentModel(null, newEstablishmentCnpj.get().trim(), name, name, null,
                establishments.isEmpty(), true));
        newEstablishmentCnpj.set("");
        newEstablishmentName.set("");
    }

    public void removeEstablishment() {
        var establishment = selectedEstablishment.get();
        if (establishment != null) {
            establishments.remove(establishment);
            selectedEstablishment.set(null);
        }
    }

    public void addIdentifier() {
        if (newIdentifierValue.get().isBlank()) {
            return;
        }
        identifiers.add(new ClientIdentifierModel(null, identifierType.get(), newIdentifierValue.get().trim(),
                ClientIdentifierSemanticRoleModel.PRIMARY_TAXPAYER, identifiers.size(), true, true));
        newIdentifierValue.set("");
    }

    public void removeIdentifier() {
        var identifier = selectedIdentifier.get();
        if (identifier != null) {
            identifiers.remove(identifier);
            selectedIdentifier.set(null);
        }
    }

    // ================================================================ mensagens personalizadas

    public void newTemplate() {
        resetTemplateEditor(true);
        status.accept(existingClient.get()
                ? "Nova mensagem personalizada. Os botões abaixo inserem as informações variáveis para você."
                : "Cadastre o cliente antes de criar uma mensagem personalizada.");
    }

    public void editSelectedTemplate() {
        var template = selectedTemplate.get();
        if (template == null) {
            status.accept("Selecione uma mensagem na lista para editar.");
            return;
        }
        editingTemplateId = template.id();
        editingTemplateVersion = template.version();
        editingTemplateDocumentTypeId = template.documentTypeId();
        editingTemplateSignatureMode = template.signatureMode();
        editingTemplateIsDefault = template.isDefault();
        editingTemplateIsActive = template.isActive();
        templateName.set(template.name());
        templateSubject.set(template.subjectTemplate());
        templateBody.set(template.bodyTemplate());
        editingTemplate.set(true);
        status.accept("Editando “" + template.name() + "”. As alterações só serão aplicadas ao clicar em Atualizar mensagem.");
    }

    public void saveTemplate() {
        if (currentClientId == null) {
            feedback("Cadastre ou abra um cliente antes de criar uma mensagem personalizada. Assim ela não será confundida com o modelo geral do escritório.", true);
            status.accept(feedbackMessage.get());
            return;
        }
        var validation = validateTemplateDraft();
        if (validation != null) {
            feedback(validation, true);
            status.accept(validation);
            return;
        }
        var clientId = currentClientId;
        var templateId = editingTemplateId;
        var isDefault = templateId != null ? editingTemplateIsDefault
                : templates.stream().noneMatch(t -> t.isDefault() && clientId.equals(t.clientId()));
        var request = new MessageTemplateMutationRequest(editingTemplateVersion, clientId, editingTemplateDocumentTypeId,
                templateName.get().trim(), templateSubject.get(), templateBody.get(), editingTemplateSignatureMode,
                isDefault, editingTemplateIsActive);
        tasks.run(() -> catalog.saveTemplate(templateId, request), saved -> {
            replaceTemplate(saved);
            selectedTemplate.set(saved);
            resetTemplateEditor(false);
            feedback("Mensagem personalizada " + (templateId != null ? "atualizada" : "criada")
                    + " com sucesso. Nenhum e-mail foi enviado.", false);
            status.accept(feedbackMessage.get());
            catalogChanged.run();
        }, this::fail);
    }

    public void toggleTemplateActive() {
        templateArchiveConfirmation.set(false);
        var existing = selectedTemplate.get();
        if (existing == null) {
            return;
        }
        tasks.run(() -> catalog.setTemplateActive(existing.id(), existing.version(), !existing.isActive()), updated -> {
            replaceTemplate(updated);
            selectedTemplate.set(updated);
            if (updated.id().equals(editingTemplateId)) {
                editingTemplateVersion = updated.version();
                editingTemplateIsActive = updated.isActive();
            }
            feedback(updated.isActive() ? "Mensagem reativada." : "Mensagem inativada.", false);
            status.accept(feedbackMessage.get());
            catalogChanged.run();
        }, this::fail);
    }

    public void archiveTemplate() {
        var template = selectedTemplate.get();
        if (template == null) {
            feedback("Selecione uma mensagem salva antes de excluí-la.", true);
            return;
        }
        if (template.isActive()) {
            feedback("Inative a mensagem antes de excluí-la da lista.", true);
            status.accept(feedbackMessage.get());
            return;
        }
        if (!templateArchiveConfirmation.get()) {
            templateArchiveConfirmation.set(true);
            feedback("Clique novamente para confirmar. A mensagem sairá da lista, mas a auditoria permanecerá protegida.", false);
            status.accept(feedbackMessage.get());
            return;
        }
        tasks.run(() -> catalog.archiveTemplate(template.id(), new ArchiveRequest(template.version(), "Excluída da lista pelo operador.")), () -> {
            templates.remove(template);
            resetTemplateEditor(true);
            feedback("Mensagem “" + template.name() + "” excluída da lista; o registro de auditoria foi preservado.", false);
            status.accept(feedbackMessage.get());
            catalogChanged.run();
        }, error -> {
            templateArchiveConfirmation.set(false);
            fail(error);
        });
    }

    public void applyStandardTemplate(PersonTypeModel type) {
        if (!existingClient.get()) {
            feedback("Cadastre ou abra um cliente antes de preparar uma mensagem padrão.", true);
            status.accept(feedbackMessage.get());
            return;
        }
        var preset = StandardMessageTemplatePresets.forType(type);
        resetTemplateEditor(true);
        templateName.set(preset.name());
        templateSubject.set(preset.subjectTemplate());
        templateBody.set(preset.bodyTemplate());
        var label = type == PersonTypeModel.LEGAL_ENTITY ? "empresa" : "pessoa física";
        feedback("Mensagem padrão para " + label + " preenchida. Você pode salvar como está ou ajustar antes de criar.", false);
        status.accept(feedbackMessage.get());
    }

    public void insertPlaceholder(String key) {
        if (!existingClient.get()) {
            feedback("Cadastre ou abra um cliente antes de personalizar a mensagem.", true);
            return;
        }
        var token = MessageTemplatePlaceholderCatalog.toToken(key);
        if ("subject".equals(placeholderTarget.get().key())) {
            templateSubject.set(templateSubject.get().isBlank() ? token : templateSubject.get() + " " + token);
            status.accept("Informação variável adicionada ao assunto da mensagem.");
        } else {
            var body = templateBody.get();
            templateBody.set(body.isBlank() ? token : body + (body.endsWith("\n") ? "" : " ") + token);
            status.accept("Informação variável adicionada ao texto da mensagem.");
        }
    }

    /** Mesmas mensagens de {@code TryValidateTemplateDraft}. Retorna {@code null} se válido. */
    public String validateTemplateDraft() {
        if (templateName.get().isBlank()) {
            return "Informe um nome para identificar esta mensagem. Esse nome não é enviado ao cliente.";
        }
        if (templateName.get().trim().length() > 160) {
            return "O nome da mensagem pode ter no máximo 160 caracteres. O texto do e-mail continua aceitando até 20.000.";
        }
        if (templateSubject.get().isBlank() || templateSubject.get().length() > 500) {
            return "Informe um assunto com até 500 caracteres.";
        }
        if (templateBody.get().isBlank() || templateBody.get().length() > 20_000) {
            return "Informe o texto da mensagem com até 20.000 caracteres.";
        }
        var unknown = new LinkedHashSet<String>();
        unknown.addAll(MessageTemplatePlaceholderCatalog.validate(templateSubject.get()).unknownKeys());
        unknown.addAll(MessageTemplatePlaceholderCatalog.validate(templateBody.get()).unknownKeys());
        if (!unknown.isEmpty()) {
            return "Há informações variáveis desconhecidas: " + String.join(", ", unknown)
                    + ". Remova-as ou use os botões disponíveis no editor.";
        }
        return null;
    }

    private void replaceTemplate(MessageTemplateModel template) {
        for (var i = 0; i < templates.size(); i++) {
            if (templates.get(i).id().equals(template.id())) {
                templates.set(i, template);
                return;
            }
        }
        templates.add(template);
    }

    private void resetTemplateEditor(boolean clearSelection) {
        editingTemplateId = null;
        editingTemplateVersion = 0;
        editingTemplateDocumentTypeId = null;
        editingTemplateSignatureMode = SignatureModeModel.ORGANIZATION;
        editingTemplateIsDefault = false;
        editingTemplateIsActive = true;
        if (clearSelection) {
            selectedTemplate.set(null);
        }
        templateName.set("");
        templateSubject.set("");
        templateBody.set("");
        editingTemplate.set(false);
    }

    // ================================================================ apoio

    private void feedback(String message, boolean error) {
        feedbackMessage.set(message);
        feedbackIsError.set(error);
    }

    private void fail(Throwable error) {
        var message = ErrorMessages.friendly(error);
        feedback(message, true);
        status.accept(message);
    }

    private static String displayName(ClientDetails details) {
        return details.preferredName() == null || details.preferredName().isBlank()
                ? details.legalNameOrFullName() : details.preferredName();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** Cliente em edição ({@code null} para um cadastro novo). */
    public UUID currentClientId() {
        return currentClientId;
    }

    /** Somente para testes. */
    List<UUID> loadedTaxIdKeys() {
        return new ArrayList<>(clientTaxIds.keySet());
    }
}
