package br.com.contadoresassociados.folhas.application.preferences;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Preferências locais do usuário (pastas, competência selecionada, sessão, retenção e visibilidade do histórico). */
public record WorkspacePreferences(String inputFolderPath, String documentArchiveDirectory,
        String reportOutputDirectory, boolean includeSubfolders, Integer selectedYear, Integer selectedMonth,
        boolean keepEmailSession, int retentionReviewMonths, String updateChannel,
        HistoryVisibilityState historyVisibility) {

    public WorkspacePreferences {
        if (retentionReviewMonths < 0 || retentionReviewMonths > 120) {
            throw new IllegalArgumentException("retentionReviewMonths deve estar entre 0 e 120.");
        }
        if (selectedMonth != null && (selectedMonth < 1 || selectedMonth > 12)) {
            selectedMonth = null;
        }
        updateChannel = "beta".equalsIgnoreCase(updateChannel) ? "beta" : "stable";
        historyVisibility = historyVisibility == null ? HistoryVisibilityState.EMPTY : historyVisibility;
    }

    public static WorkspacePreferences defaults(OffsetDateTime nowBrt) {
        return new WorkspacePreferences(null, null, null, true, nowBrt.getYear(), nowBrt.getMonthValue(), true, 0,
                "stable", HistoryVisibilityState.EMPTY);
    }

    public WorkspacePreferences withHistoryVisibility(HistoryVisibilityState state) {
        return new WorkspacePreferences(inputFolderPath, documentArchiveDirectory, reportOutputDirectory,
                includeSubfolders, selectedYear, selectedMonth, keepEmailSession, retentionReviewMonths, updateChannel,
                state);
    }

    public enum HistoryVisibilityRuleKind { ALL_UNTIL_NOW, SELECTED_OPERATIONAL_PERIOD, CALENDAR_DAY, CLOCK_HOUR, CUSTOM_INTERVAL }

    public record HistoryVisibilityRule(UUID id, HistoryVisibilityRuleKind kind, OffsetDateTime createdAtUtc,
            OffsetDateTime startUtc, OffsetDateTime endExclusiveUtc, Integer year, Integer month, List<UUID> eventIds) {
        public HistoryVisibilityRule {
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
        }
    }

    public record HistoryVisibilityState(List<HistoryVisibilityRule> rules) {
        public static final HistoryVisibilityState EMPTY = new HistoryVisibilityState(List.of());

        public HistoryVisibilityState {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public interface Store {
        Optional<WorkspacePreferences> load();

        void save(WorkspacePreferences preferences);
    }
}
