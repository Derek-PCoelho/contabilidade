package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Preferências e checklist do piloto em {@code workspace_preferences} (mesmas chaves da versão .NET). */
public final class SqliteKeyValueStores {

    static final String PREFERENCES_KEY = "desktop.workspace.v1";
    static final String PILOT_PREFIX = "desktop.pilot.v1.";

    private SqliteKeyValueStores() {
    }

    static Optional<String> get(LocalDatabase db, String key) {
        return db.read(c -> {
            try (var ps = c.prepareStatement("SELECT \"JsonPayload\" FROM \"workspace_preferences\" WHERE \"Key\" = ?")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    static void put(LocalDatabase db, Clock clock, String key, String json) {
        db.transaction(c -> {
            try (var ps = c.prepareStatement("""
                    INSERT INTO "workspace_preferences" ("Key", "JsonPayload", "UpdatedAtUtc") VALUES (?, ?, ?)
                    ON CONFLICT ("Key") DO UPDATE SET "JsonPayload" = excluded."JsonPayload",
                      "UpdatedAtUtc" = excluded."UpdatedAtUtc\"""")) {
                ps.setString(1, key);
                ps.setString(2, json);
                ps.setLong(3, SqliteCodec.dateTimeOffset(clock.nowUtc()));
                return ps.executeUpdate();
            }
        });
    }

    static String pilotKey(String scopeKey) {
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(scopeKey.getBytes(StandardCharsets.UTF_8));
            return PILOT_PREFIX + HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Preferences implements WorkspacePreferences.Store {
        private final LocalDatabase db;
        private final Clock clock;

        public Preferences(LocalDatabase db, Clock clock) {
            this.db = db;
            this.clock = clock;
        }

        @Override
        public Optional<WorkspacePreferences> load() {
            return get(db, PREFERENCES_KEY).map(json -> {
                try {
                    return Json.read(json, WorkspacePreferences.class);
                } catch (RuntimeException e) {
                    return null;
                }
            });
        }

        @Override
        public void save(WorkspacePreferences preferences) {
            put(db, clock, PREFERENCES_KEY, Json.write(preferences));
        }
    }

    public static final class PilotChecklist implements PilotReadiness.Store {
        private final LocalDatabase db;
        private final Clock clock;

        public PilotChecklist(LocalDatabase db, Clock clock) {
            this.db = db;
            this.clock = clock;
        }

        @Override
        public PilotReadiness.ChecklistWorkspace load(String scopeKey) {
            return get(db, pilotKey(scopeKey)).map(json -> Json.read(json, PilotReadiness.ChecklistWorkspace.class))
                    .filter(ws -> scopeKey.equals(ws.scopeKey()))
                    .orElse(PilotReadiness.ChecklistWorkspace.empty(scopeKey));
        }

        @Override
        public void save(PilotReadiness.ChecklistWorkspace workspace) {
            put(db, clock, pilotKey(workspace.scopeKey()), Json.write(workspace));
        }
    }

    /** Última {@code sequence} de manifesto aceita por canal (anti-replay das atualizações, 7.2). */
    public static final class UpdateSequences
            implements br.com.contadoresassociados.folhas.infrastructure.updates.SignedFeedAppUpdateService.SequenceStore {
        private final LocalDatabase db;
        private final Clock clock;

        public UpdateSequences(LocalDatabase db, Clock clock) {
            this.db = db;
            this.clock = clock;
        }

        @Override
        public long lastAccepted(String channelKey) {
            return get(db, "desktop.updates.sequence." + channelKey).map(v -> {
                try {
                    return Long.parseLong(v.strip());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }).orElse(0L);
        }

        @Override
        public void accept(String channelKey, long sequence) {
            if (sequence > lastAccepted(channelKey)) {
                put(db, clock, "desktop.updates.sequence." + channelKey, Long.toString(sequence));
            }
        }
    }
}
