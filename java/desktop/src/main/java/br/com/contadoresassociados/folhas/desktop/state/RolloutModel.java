package br.com.contadoresassociados.folhas.desktop.state;

import br.com.contadoresassociados.folhas.application.clients.ClientCatalogService;
import br.com.contadoresassociados.folhas.application.identity.PermissionDeniedException;
import br.com.contadoresassociados.folhas.application.incidents.IncidentManagement;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness.ChecklistKey;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocument;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.desktop.DesktopRollout;
import br.com.contadoresassociados.folhas.desktop.ui.ErrorMessages;
import br.com.contadoresassociados.folhas.desktop.ui.UiTasks;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Painéis de suporte "Piloto supervisionado" e "Produção gradual" (Configurações → Detalhes
 * técnicos). Porte do trecho {@code Pilot*}/{@code Production*} do {@code MainViewModel}.
 * O painel de produção é somente informativo: nenhuma trava pode ser aberta pela tela.
 */
public final class RolloutModel {

    /** Rótulos da checklist, na ordem da tela .NET. */
    public static final Map<ChecklistKey, String> CHECKLIST_LABELS = labels();

    private final DesktopRollout rollout;
    private final PilotReadiness.Service pilot;
    private final IncidentManagement.Service incidents;
    private final ClientCatalogService catalog;
    private final DocumentsModel documents;
    private final DispatchModel dispatch;
    private final ShellState shell;
    private final UiTasks tasks;
    private PilotReadiness.Snapshot snapshot;

    // ---------------------------------------------------------------- piloto
    public final Map<ChecklistKey, BooleanProperty> checklist = new EnumMap<>(ChecklistKey.class);
    public final BooleanProperty pilotReady = new SimpleBooleanProperty(false);
    public final IntegerProperty checklistProgress = new SimpleIntegerProperty(0);
    public final StringProperty pilotStatusTitle = new SimpleStringProperty("Em preparação");
    public final StringProperty pilotStatusSummary = new SimpleStringProperty(
            "Conclua o checklist nas duas plataformas antes de iniciar o uso supervisionado.");
    public final StringProperty pilotMetricsSummary = new SimpleStringProperty(
            "Nenhuma operação do piloto foi registrada nesta instalação.");
    public final BooleanProperty saving = new SimpleBooleanProperty(false);

    public RolloutModel(DesktopRollout rollout, PilotReadiness.Service pilot, IncidentManagement.Service incidents,
            ClientCatalogService catalog, DocumentsModel documents, DispatchModel dispatch, ShellState shell,
            UiTasks tasks) {
        this.rollout = Objects.requireNonNull(rollout, "rollout");
        this.pilot = pilot;
        this.incidents = incidents;
        this.catalog = catalog;
        this.documents = documents;
        this.dispatch = dispatch;
        this.shell = shell;
        this.tasks = tasks;
        for (var key : ChecklistKey.values()) {
            checklist.put(key, new SimpleBooleanProperty(false));
        }
    }

    // ================================================================ piloto

    public boolean pilotMode() {
        return rollout.pilot().enabled();
    }

    public String pilotEnvironmentLabel() {
        return "staging".equalsIgnoreCase(rollout.pilot().environmentName()) ? "Ambiente de homologação"
                : "Ambiente não validado";
    }

    public String pilotSafeguardSummary() {
        return rollout.pilot().allowSend() ? "Atenção: a trava de envio do piloto está aberta."
                : "Teste e Rascunho disponíveis. Envio aos clientes bloqueado.";
    }

    /** Carrega o checklist gravado e as métricas atuais (somente com o piloto habilitado). */
    public void refresh() {
        if (!pilotMode() || pilot == null) {
            return;
        }
        var docs = List.copyOf(documents.documents);
        var attempts = List.copyOf(dispatch.attempts);
        tasks.run(() -> pilot.load(metrics(docs, attempts)), this::applySnapshot,
                error -> shell.status(friendly(error)));
    }

    /** "Salvar checklist de homologação" — grava e audita somente os itens alterados. */
    public void saveChecklist() {
        if (!pilotMode() || pilot == null) {
            shell.status("O checklist do piloto não está disponível nesta instalação.");
            return;
        }
        var values = new EnumMap<ChecklistKey, Boolean>(ChecklistKey.class);
        checklist.forEach((key, value) -> values.put(key, value.get()));
        var docs = List.copyOf(documents.documents);
        var attempts = List.copyOf(dispatch.attempts);
        saving.set(true);
        tasks.run(() -> pilot.update(values, metrics(docs, attempts)), (PilotReadiness.Snapshot saved) -> {
            saving.set(false);
            applySnapshot(saved);
            shell.status(saved.ready()
                    ? "Checklist concluído. O piloto está pronto apenas para execução supervisionada em homologação."
                    : "Checklist salvo. Os itens pendentes continuam bloqueando o início do piloto.");
        }, error -> {
            saving.set(false);
            shell.status(friendly(error));
        });
    }

    private void applySnapshot(PilotReadiness.Snapshot value) {
        snapshot = value;
        value.checklist().items().forEach(item -> checklist.get(item.key()).set(item.confirmed()));
        checklistProgress.set(value.requiredChecklistCount() == 0 ? 0
                : value.confirmedChecklistCount() * 100 / value.requiredChecklistCount());
        applyMetrics(value.metrics());
    }

    /** Mesmo texto e mesmas regras de interrupção de {@code ApplyPilotMetricsPresentation}. */
    private void applyMetrics(PilotReadiness.OperationalMetrics m) {
        pilotMetricsSummary.set(m.documentCount() + " documento(s) • " + m.testAttemptCount() + " teste(s) • "
                + m.draftAttemptCount() + " rascunho(s) • " + m.failedOrAmbiguousAttemptCount() + " pendência(s) técnica(s)");
        var max = rollout.pilot().maximumClients();
        String stop = m.sendAttemptCount() > 0 ? "Uma tentativa de envio foi registrada. Interrompa o piloto e investigue."
                : m.openHighOrCriticalIncidentCount() > 0 ? "Há ocorrência alta ou crítica em aberto. O piloto está interrompido."
                : m.failedOrAmbiguousAttemptCount() > 0 ? "Há falha ou resultado incerto aguardando tratamento."
                : m.clientCount() > max ? "O limite de " + max + " clientes do piloto foi excedido." : null;
        if (stop != null) {
            pilotReady.set(false);
            pilotStatusTitle.set("Piloto interrompido");
            pilotStatusSummary.set(stop);
        } else if (snapshot != null) {
            pilotReady.set(snapshot.ready());
            pilotStatusTitle.set(snapshot.ready() ? "Pronto para supervisão" : "Em preparação");
            pilotStatusSummary.set(snapshot.ready()
                    ? "Todos os controles locais foram confirmados. Isso não autoriza produção nem envio aos clientes."
                    : snapshot.blockers().isEmpty() ? "Conclua os controles operacionais pendentes."
                    : snapshot.blockers().getFirst());
        }
    }

    /**
     * Métricas do piloto (porte de {@code BuildPilotMetrics}). Roda fora da thread da interface,
     * sobre cópias das listas tiradas nela.
     */
    PilotReadiness.OperationalMetrics metrics(List<ReviewDocument> docs, List<DeliveryAttempt> attempts) {
        Set<UUID> clientIds = Stream.concat(
                catalog.search(null, null, null, 0, 500).items().stream().map(ClientListItem::id),
                docs.stream().map(ReviewDocument::clientId))
                .filter(Objects::nonNull).collect(Collectors.toSet());
        long highRisk;
        try {
            highRisk = incidents.load().incidents().stream()
                    .filter(i -> (i.severity() == IncidentManagement.Severity.HIGH
                            || i.severity() == IncidentManagement.Severity.CRITICAL)
                            && i.status() != IncidentManagement.Status.CLOSED)
                    .count();
        } catch (PermissionDeniedException e) {
            highRisk = 0;
        }
        return new PilotReadiness.OperationalMetrics(clientIds.size(), docs.size(),
                count(docs, d -> d.state() == ReviewDocumentState.READY || d.state() == ReviewDocumentState.GROUPED
                        || d.state() == ReviewDocumentState.APPROVED),
                count(docs, d -> d.state() == ReviewDocumentState.BLOCKED),
                count(docs, d -> d.state() == ReviewDocumentState.DUPLICATE),
                count(attempts, a -> a.mode() == DispatchOperationMode.TEST),
                count(attempts, a -> a.mode() == DispatchOperationMode.DRAFT),
                count(attempts, a -> a.mode() == DispatchOperationMode.SEND),
                count(attempts, a -> a.state() == DeliveryAttemptState.FAILED_TRANSIENT
                        || a.state() == DeliveryAttemptState.FAILED_PERMANENT
                        || a.state() == DeliveryAttemptState.AMBIGUOUS || a.state() == DeliveryAttemptState.PENDING),
                (int) highRisk);
    }

    private static <T> int count(List<T> list, java.util.function.Predicate<T> predicate) {
        return (int) list.stream().filter(predicate).count();
    }

    private static String friendly(Throwable error) {
        return error instanceof PermissionDeniedException
                ? "Seu perfil não tem permissão para alterar o checklist do piloto. Peça a um gestor ou administrador."
                : ErrorMessages.friendly(error);
    }

    // ================================================================ produção

    public boolean productionVisible() {
        return rollout.productionPanelVisible();
    }

    public boolean productionReady() {
        return rollout.readiness().readyForSend();
    }

    public String productionStageLabel() {
        return switch (rollout.production().stage()) {
            case LIMITED -> "Etapa limitada";
            case GRADUAL -> "Etapa gradual";
            case CLOSED -> "Etapa fechada";
        };
    }

    public String productionStatusTitle() {
        return productionReady() ? "Abertura gradual autorizada" : "Produção bloqueada";
    }

    public String productionStatusSummary() {
        var blockers = rollout.readiness().blockers();
        return productionReady()
                ? "Os controles formais estão válidos. Cada envio ainda exige papel autorizado, MFA, aprovação e preflight central."
                : blockers.isEmpty() ? "A produção permanece fechada até a aprovação de todos os controles externos."
                : blockers.getFirst();
    }

    /** Portões na ordem da tela .NET ({@code ✓} concluído, {@code ○} pendente). */
    public List<String> productionGates() {
        var o = rollout.production();
        return List.of(gate(o.pilotApproved() && !rollout.pilot().enabled(), "Piloto aceito formalmente"),
                gate(o.stableReleaseApproved(), "Stable assinada e aprovada"),
                gate(o.backupRestoreDrillCompleted(), "Backup e restauração exercitados"),
                gate(o.monitoringReady(), "Monitoramento e alertas prontos"),
                gate(o.incidentResponseReady(), "Resposta a incidentes validada"),
                gate(o.supportReady(), "Suporte e responsáveis confirmados"));
    }

    /** Todas as pendências da avaliação — a tela .NET mostrava só a primeira. */
    public List<String> productionBlockers() {
        return rollout.readiness().blockers();
    }

    public String productionLimitsSummary() {
        var o = rollout.production();
        return "Até " + o.maximumBatchSize() + " mensagem(ns) por sequência e " + o.maximumDailySends()
                + " por dia, com contagem central por organização.";
    }

    public String productionRolesSummary() {
        return rollout.readiness().allowedRoles().isEmpty() ? "Nenhum papel possui liberação válida."
                : "Papéis previstos: Gestor, Administrador e Proprietário técnico — sempre com MFA.";
    }

    private static String gate(boolean done, String label) {
        return (done ? "✓ " : "○ ") + label;
    }

    private static Map<ChecklistKey, String> labels() {
        var map = new EnumMap<ChecklistKey, String>(ChecklistKey.class);
        map.put(ChecklistKey.NON_PRODUCTION_DATA_CONFIRMED, "Dados fictícios ou anonimizados conferidos");
        map.put(ChecklistKey.CONTROLLED_ACCOUNT_CONFIRMED, "Conta e caixa de teste controladas");
        map.put(ChecklistKey.MAC_OS_STATION_VALIDATED, "Estação macOS validada");
        map.put(ChecklistKey.WINDOWS_STATION_VALIDATED, "Estação Windows validada");
        map.put(ChecklistKey.BACKUP_RESTORE_VALIDATED, "Cópia e restauração ensaiadas");
        map.put(ChecklistKey.ROLLBACK_VALIDATED, "Reversão para a versão anterior ensaiada");
        return java.util.Collections.unmodifiableMap(map);
    }
}
