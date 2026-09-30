package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.WorkspaceConflictException;
import br.com.contadoresassociados.folhas.application.pilot.PilotReadiness;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences;
import br.com.contadoresassociados.folhas.contracts.dispatch.*;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import br.com.contadoresassociados.folhas.contracts.sync.ClientSyncItem;
import br.com.contadoresassociados.folhas.contracts.sync.PullSyncResponse;
import br.com.contadoresassociados.folhas.contracts.sync.UpsertClientCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlitePersistenceTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    static final OffsetDateTime NOW_UTC = NOW.atOffset(ZoneOffset.UTC);
    static final String SCOPE = "org:1|user:2";

    @TempDir
    Path dir;

    @Test
    void codecMatchesEfCoreDateTimeOffsetToBinaryConverter() {
        assertThat(SqliteCodec.dateTimeOffset(OffsetDateTime.parse("2026-09-30T12:00:00Z")))
                .isEqualTo(1309211983872000000L);
        assertThat(SqliteCodec.dateTimeOffset(OffsetDateTime.parse("2026-09-30T09:00:00-03:00")))
                .isEqualTo(1309211762688001868L);
        var odt = OffsetDateTime.parse("2026-09-30T09:00:00.1234-03:00");
        assertThat(SqliteCodec.dateTimeOffset(SqliteCodec.dateTimeOffset(odt))).isEqualTo(odt);
        assertThat(SqliteCodec.pascal(ReviewGroupState.READY_FOR_REVIEW)).isEqualTo("ReadyForReview");
        assertThat(SqliteCodec.guid(new UUID(0, 0xABCL))).isEqualTo("00000000-0000-0000-0000-000000000ABC");
    }

    @Test
    void schemaMatchesEfHistoryAndIsIdempotent() throws Exception {
        var file = dir.resolve("cache.db");
        LocalDatabase.open(file).close();
        LocalDatabase.open(file).close();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var rs = c.createStatement().executeQuery("SELECT COUNT(*) FROM \"__EFMigrationsHistory\"")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(7);
        }
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            assertThat(Files.getPosixFilePermissions(file).toString()).doesNotContain("GROUP", "OTHERS");
        }
    }

    @Test
    void existingDotNetDatabaseIsAdoptedWithoutRecreatingTables() throws Exception {
        var file = dir.resolve("legacy.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var st = c.createStatement()) {
            st.execute("CREATE TABLE \"__EFMigrationsHistory\" (\"MigrationId\" TEXT NOT NULL PRIMARY KEY, \"ProductVersion\" TEXT NOT NULL)");
            for (var entry : LocalSchemaMigrator.EF_MIGRATIONS.entrySet()) {
                for (var sql : entry.getValue()) {
                    st.execute(sql);
                }
                st.execute("INSERT INTO \"__EFMigrationsHistory\" VALUES ('" + entry.getKey() + "', '10.0.11')");
            }
            // Linha gravada pela versão .NET (JSON camelCase, enums numéricos).
            st.execute("INSERT INTO \"workspace_preferences\" VALUES ('desktop.workspace.v1', "
                    + "'{\"inputFolderPath\":\"C:\\\\Folhas\",\"includeSubfolders\":true,\"selectedYear\":2026,"
                    + "\"selectedMonth\":9,\"keepEmailSession\":false,\"retentionReviewMonths\":12,"
                    + "\"updateChannel\":\"stable\",\"historyVisibility\":{\"rules\":[]}}', 0)");
        }
        try (var db = LocalDatabase.open(file)) {
            var prefs = new SqliteKeyValueStores.Preferences(db, Clock.fixed(NOW)).load().orElseThrow();
            assertThat(prefs.inputFolderPath()).isEqualTo("C:\\Folhas");
            assertThat(prefs.retentionReviewMonths()).isEqualTo(12);
            assertThat(prefs.keepEmailSession()).isFalse();
        }
    }

    @Test
    void reviewStoreRoundTripsAndEnforcesOptimisticVersion() {
        try (var db = LocalDatabase.inMemory()) {
            var store = new SqliteDocumentReviewStore(db);
            var ws = store.load(SCOPE);
            assertThat(ws.version()).isZero();
            var doc = document();
            var audit = new ReviewAuditEvent(UUID.randomUUID(), SCOPE, "a", NOW_UTC, "document.imported", doc.id(), null,
                    null, "x", null, "c");
            var saved = store.save(ws.toBuilder().documents(List.of(doc)).auditEvents(List.of(audit)).build(), 0);
            assertThat(saved.version()).isEqualTo(1);
            var loaded = store.load(SCOPE);
            assertThat(loaded.documents()).singleElement().satisfies(d -> {
                assertThat(d.id()).isEqualTo(doc.id());
                assertThat(d.period().year()).isEqualTo(2026);
            });
            assertThat(loaded.auditEvents()).hasSize(1);
            assertThatThrownBy(() -> store.save(loaded, 0)).isInstanceOf(WorkspaceConflictException.class);
            store.save(loaded, 1);
            assertThat(store.load(SCOPE).auditEvents()).hasSize(1);
            assertThat(store.load("org:2|user:9").documents()).isEmpty();
        }
    }

    @Test
    void dispatchStoreKeepsAttemptsAndUniqueIdempotency() {
        try (var db = LocalDatabase.inMemory()) {
            var store = new SqliteDispatchWorkflowStore(db);
            var attempt = new DeliveryAttempt(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    1, DispatchOperationMode.TEST, DeliveryAttemptState.PENDING, "fake.local", "k:1", "fp", null, null,
                    null, null, NOW_UTC, null);
            var ws = store.save(DispatchWorkspace.empty(SCOPE).toBuilder().attempts(List.of(attempt)).build(), 0);
            var done = attempt.toBuilder().state(DeliveryAttemptState.ACCEPTED_BY_PROVIDER).build();
            ws = store.save(ws.toBuilder().attempts(List.of(done)).build(), ws.version());
            assertThat(store.load(SCOPE).attempts()).singleElement()
                    .satisfies(a -> assertThat(a.state()).isEqualTo(DeliveryAttemptState.ACCEPTED_BY_PROVIDER));
            var dup = new DeliveryAttempt(UUID.randomUUID(), attempt.batchId(), attempt.dispatchItemId(),
                    attempt.groupId(), 2, DispatchOperationMode.TEST, DeliveryAttemptState.PENDING, "fake.local", "k:1",
                    "fp", null, null, null, null, NOW_UTC, null);
            var withDup = ws.toBuilder().attempts(List.of(done, dup)).build();
            var version = ws.version();
            assertThatThrownBy(() -> store.save(withDup, version)).isInstanceOf(LocalDatabase.LocalStorageException.class);
            assertThat(store.load(SCOPE).version()).isEqualTo(version);
        }
    }

    @Test
    void syncStoreQueuesRetriesAndNeverRegressesCache() {
        try (var db = LocalDatabase.inMemory()) {
            var store = new SqliteLocalSyncStore(db, Clock.fixed(NOW));
            var cmd = new UpsertClientCommand(UUID.randomUUID(), UUID.randomUUID(), "Cliente", true, 3);
            store.enqueue(cmd);
            store.enqueue(cmd);
            assertThat(store.readyOperations(NOW_UTC)).hasSize(1);
            store.scheduleRetry(cmd.operationId(), NOW_UTC.plusMinutes(1));
            assertThat(store.readyOperations(NOW_UTC)).isEmpty();
            assertThat(store.readyOperations(NOW_UTC.plusMinutes(2))).singleElement()
                    .satisfies(o -> assertThat(o.attemptCount()).isEqualTo(1));
            var id = UUID.randomUUID();
            store.applyPull(new PullSyncResponse(10, List.of(new ClientSyncItem(id, "Novo", true, 5, NOW_UTC)), false));
            store.applyPull(new PullSyncResponse(4, List.of(new ClientSyncItem(id, "Antigo", true, 2, NOW_UTC)), false));
            assertThat(store.checkpoint()).isEqualTo(10);
            assertThat(store.clients()).singleElement().satisfies(c -> assertThat(c.displayName()).isEqualTo("Novo"));
        }
    }

    @Test
    void pilotChecklistUsesDotNetKeyAndJsonShape() {
        try (var db = LocalDatabase.inMemory()) {
            var store = new SqliteKeyValueStores.PilotChecklist(db, Clock.fixed(NOW));
            var ws = PilotReadiness.ChecklistWorkspace.empty(SCOPE);
            store.save(ws);
            assertThat(SqliteKeyValueStores.pilotKey(SCOPE)).startsWith("desktop.pilot.v1.").hasSize(17 + 32);
            var json = SqliteKeyValueStores.get(db, SqliteKeyValueStores.pilotKey(SCOPE)).orElseThrow();
            assertThat(json).contains("\"isConfirmed\":false");
            assertThat(store.load(SCOPE).items()).hasSize(6);
            var prefs = new SqliteKeyValueStores.Preferences(db, Clock.fixed(NOW));
            prefs.save(WorkspacePreferences.defaults(NOW_UTC));
            assertThat(prefs.load()).isPresent();
        }
    }

    static ReviewDocument document() {
        var period = new DocumentPeriod(DocumentPeriodKind.MONTHLY, 3, 2026, null, null, null, "03/2026");
        return new ReviewDocument(UUID.randomUUID(), "/tmp/a.pdf", "a.pdf", "ab".repeat(32), 10, 1,
                RecognizedDocumentType.PAYROLL, "p1", UUID.randomUUID(), null, "Empresa", "**", ClientResolutionMethod.NONE,
                java.math.BigDecimal.ONE, List.of(), List.of(), List.of(), List.of(), period, "k", ReviewDocumentState.READY,
                1, null, List.of(), NOW_UTC, NOW_UTC, null, null);
    }
}
