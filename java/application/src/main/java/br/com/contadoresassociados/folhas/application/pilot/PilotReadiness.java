package br.com.contadoresassociados.folhas.application.pilot;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchExecutionContextAccessor;
import br.com.contadoresassociados.folhas.application.identity.PermissionDeniedException;
import br.com.contadoresassociados.folhas.domain.identity.AppPermission;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Piloto supervisionado: checklist auditado e avaliação de prontidão.
 * Pendência 2.10: alterar o checklist exige {@code settings.manage}; consultar exige {@code audit.read}
 * ou {@code documents.process}.
 */
public final class PilotReadiness {

    private PilotReadiness() {
    }

    public record Options(boolean enabled, String environmentName, boolean allowTest, boolean allowDraft,
            boolean allowSend, boolean requireNonProductionData, int maximumClients) {
        public static Options defaults() {
            return new Options(false, "staging", true, true, false, true, 5);
        }
    }

    public enum ChecklistKey {
        NON_PRODUCTION_DATA_CONFIRMED, CONTROLLED_ACCOUNT_CONFIRMED, MAC_OS_STATION_VALIDATED,
        WINDOWS_STATION_VALIDATED, BACKUP_RESTORE_VALIDATED, ROLLBACK_VALIDATED
    }

    /** O nome JSON {@code isConfirmed} mantém compatibilidade com o checklist gravado pela versão .NET. */
    public record ChecklistItem(ChecklistKey key,
            @com.fasterxml.jackson.annotation.JsonProperty("isConfirmed") boolean confirmed, String confirmedBy,
            OffsetDateTime confirmedAtUtc) {
    }

    public record ChecklistAuditEvent(UUID id, ChecklistKey key, boolean previousValue, boolean currentValue,
            String actorId, OffsetDateTime timestampUtc) {
    }

    public record ChecklistWorkspace(String scopeKey, List<ChecklistItem> items, List<ChecklistAuditEvent> auditEvents) {
        public ChecklistWorkspace {
            items = List.copyOf(items);
            auditEvents = List.copyOf(auditEvents);
        }

        public static ChecklistWorkspace empty(String scopeKey) {
            return new ChecklistWorkspace(scopeKey, Arrays.stream(ChecklistKey.values())
                    .map(k -> new ChecklistItem(k, false, null, null)).toList(), List.of());
        }
    }

    public record OperationalMetrics(int clientCount, int documentCount, int eligibleDocumentCount,
            int blockedDocumentCount, int duplicateDocumentCount, int testAttemptCount, int draftAttemptCount,
            int sendAttemptCount, int failedOrAmbiguousAttemptCount, int openHighOrCriticalIncidentCount) {
        public static final OperationalMetrics EMPTY = new OperationalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public record Snapshot(boolean ready, int confirmedChecklistCount, int requiredChecklistCount,
            ChecklistWorkspace checklist, OperationalMetrics metrics, List<String> blockers) {
    }

    public interface Store {
        ChecklistWorkspace load(String scopeKey);

        void save(ChecklistWorkspace workspace);
    }

    public static final class Service {
        private final Store store;
        private final DispatchExecutionContextAccessor contexts;
        private final Clock clock;
        private final Options options;

        public Service(Store store, DispatchExecutionContextAccessor contexts, Clock clock, Options options) {
            this.store = store;
            this.contexts = contexts;
            this.clock = clock;
            this.options = options;
        }

        public Snapshot load(OperationalMetrics metrics) {
            Objects.requireNonNull(metrics, "metrics");
            var ctx = contexts.current();
            if (!ctx.has(AppPermission.AUDIT_READ) && !ctx.has(AppPermission.DOCUMENTS_PROCESS)) {
                throw new PermissionDeniedException(AppPermission.AUDIT_READ);
            }
            return evaluate(normalize(store.load(ctx.scopeKey()), ctx.scopeKey()), metrics);
        }

        public synchronized Snapshot update(Map<ChecklistKey, Boolean> values, OperationalMetrics metrics) {
            Objects.requireNonNull(values, "values");
            var ctx = contexts.current();
            if (!ctx.has(AppPermission.SETTINGS_MANAGE)) {
                throw new PermissionDeniedException(AppPermission.SETTINGS_MANAGE);
            }
            var ws = normalize(store.load(ctx.scopeKey()), ctx.scopeKey());
            var items = new EnumMap<ChecklistKey, ChecklistItem>(ChecklistKey.class);
            ws.items().forEach(i -> items.put(i.key(), i));
            var audit = new ArrayList<>(ws.auditEvents());
            var now = clock.nowUtc();
            for (var key : ChecklistKey.values()) {
                var current = items.get(key);
                var next = values.getOrDefault(key, current.confirmed());
                if (current.confirmed() == next) {
                    continue;
                }
                items.put(key, new ChecklistItem(key, next, next ? ctx.actorId() : null, next ? now : null));
                audit.add(new ChecklistAuditEvent(UUID.randomUUID(), key, current.confirmed(), next, ctx.actorId(), now));
            }
            var updated = new ChecklistWorkspace(ctx.scopeKey(), List.copyOf(items.values()), audit);
            store.save(updated);
            return evaluate(updated, metrics);
        }

        Snapshot evaluate(ChecklistWorkspace ws, OperationalMetrics m) {
            var b = new ArrayList<String>();
            if (!options.enabled()) {
                b.add("O modo piloto não está habilitado nesta instalação.");
            }
            if (options.environmentName() == null
                    || !options.environmentName().toLowerCase(Locale.ROOT).equals("staging")) {
                b.add("O ambiente precisa estar identificado como homologação.");
            }
            if (!options.allowTest() || !options.allowDraft() || options.allowSend()) {
                b.add("O piloto exige Teste e Rascunho liberados, com Envio aos clientes bloqueado.");
            }
            if (!options.requireNonProductionData()) {
                b.add("A exigência de dados fictícios ou anonimizados precisa permanecer ativa.");
            }
            if (options.maximumClients() < 1 || options.maximumClients() > 5) {
                b.add("O limite do piloto deve ficar entre 1 e 5 clientes.");
            } else if (m.clientCount() > options.maximumClients()) {
                b.add("O piloto excedeu o limite de " + options.maximumClients() + " clientes.");
            }
            if (m.sendAttemptCount() > 0) {
                b.add("Existe tentativa de envio a destinatário registrada; interrompa e investigue.");
            }
            if (m.failedOrAmbiguousAttemptCount() > 0) {
                b.add("Há operação com falha ou resultado incerto aguardando tratamento.");
            }
            if (m.openHighOrCriticalIncidentCount() > 0) {
                b.add("Há ocorrência alta ou crítica ainda não encerrada.");
            }
            var unconfirmed = ws.items().stream().filter(i -> !i.confirmed()).count();
            if (unconfirmed > 0) {
                b.add("Faltam " + unconfirmed + (unconfirmed == 1 ? " confirmação" : " confirmações")
                        + " no checklist operacional.");
            }
            return new Snapshot(b.isEmpty(), (int) (ws.items().size() - unconfirmed), ws.items().size(), ws, m,
                    List.copyOf(b));
        }

        private static ChecklistWorkspace normalize(ChecklistWorkspace ws, String scopeKey) {
            if (ws == null) {
                return ChecklistWorkspace.empty(scopeKey);
            }
            var existing = new EnumMap<ChecklistKey, ChecklistItem>(ChecklistKey.class);
            ws.items().forEach(i -> existing.put(i.key(), i));
            var items = Arrays.stream(ChecklistKey.values())
                    .map(k -> existing.getOrDefault(k, new ChecklistItem(k, false, null, null))).toList();
            return new ChecklistWorkspace(scopeKey, items, ws.auditEvents());
        }
    }
}
