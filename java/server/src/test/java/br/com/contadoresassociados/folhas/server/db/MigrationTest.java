package br.com.contadoresassociados.folhas.server.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.server.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationTest {

    @Test
    void freshDatabaseGetsBaselineAndFixes() throws Exception {
        try (var db = TestDatabase.create()) {
            DatabaseMigrator.migrate(db.dataSource());
            try (var c = db.admin(); var st = c.createStatement()) {
                var rs = st.executeQuery("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history "
                        + "WHERE success");
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("1,2,3");
                rs = st.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = "
                        + "'IX_client_recipients_ClientId_EstablishmentId_EmailNormalized_~'");
                rs.next();
                assertThat(rs.getString(1)).contains("NULLS NOT DISTINCT");
                rs = st.executeQuery("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND NOT convalidated");
                rs.next();
                assertThat(rs.getInt(1)).isZero();
                rs = st.executeQuery("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = 'clients'");
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
            }
            // idempotente
            DatabaseMigrator.migrate(db.dataSource());
        }
    }

    @Test
    void existingEfDatabaseIsBaselinedAndKeepsData() throws Exception {
        try (var db = TestDatabase.create()) {
            var baseline = new String(DatabaseMigrator.class.getResourceAsStream("/db/migration/V1__baseline_ef_schema.sql")
                    .readAllBytes(), StandardCharsets.UTF_8);
            var org = UUID.randomUUID();
            try (var c = db.dataSource().getConnection(); var st = c.createStatement()) {
                st.execute(baseline);
                st.execute("CREATE TABLE \"__EFMigrationsHistory\" (\"MigrationId\" varchar(150) PRIMARY KEY, "
                        + "\"ProductVersion\" varchar(32) NOT NULL)");
                for (var id : new String[] {"20260820225019_InitialCentralPhase2", "20260821012511_AddPhase3ClientCatalog",
                    "20260821184105_AddPrePhase9ClientPartners", "20260822003950_AddPhase12ProductionRollout",
                    DatabaseMigrator.LAST_EF_MIGRATION}) {
                    st.execute("INSERT INTO \"__EFMigrationsHistory\" VALUES ('" + id + "', '10.0.11')");
                }
                st.execute("INSERT INTO organizations VALUES ('" + org + "', 'Escritório', 'escritorio', true, now(), now())");
                st.execute("INSERT INTO sync_changes (\"OrganizationId\", \"EntityId\", \"EntityType\", \"ChangedAtUtc\") "
                        + "VALUES ('" + org + "', gen_random_uuid(), 'client', now())");
            }
            DatabaseMigrator.migrate(db.dataSource());
            try (var c = db.admin(); var st = c.createStatement()) {
                var rs = st.executeQuery("SELECT string_agg(version, ',' ORDER BY installed_rank) FROM flyway_schema_history");
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("1,2,3");
                rs = st.executeQuery("SELECT \"LastCheckpoint\" FROM sync_counters WHERE \"OrganizationId\" = '" + org + "'");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
        }
    }

    @Test
    void unknownEfHistoryIsRefused() throws Exception {
        try (var db = TestDatabase.create()) {
            try (var c = db.dataSource().getConnection(); var st = c.createStatement()) {
                st.execute("CREATE TABLE \"__EFMigrationsHistory\" (\"MigrationId\" varchar(150) PRIMARY KEY, "
                        + "\"ProductVersion\" varchar(32) NOT NULL)");
                st.execute("INSERT INTO \"__EFMigrationsHistory\" VALUES ('20990101000000_Future', '11.0')");
            }
            assertThatThrownBy(() -> DatabaseMigrator.migrate(db.dataSource()))
                    .hasMessageContaining("migrations diferentes");
        }
    }

    @Test
    void auditTrailIsAppendOnlyAndTenantRowsAreIsolated() throws Exception {
        try (var db = TestDatabase.create()) {
            DatabaseMigrator.migrate(db.dataSource());
            var orgA = UUID.randomUUID();
            var orgB = UUID.randomUUID();
            try (var c = db.admin(); var st = c.createStatement()) {
                for (var org : new UUID[] {orgA, orgB}) {
                    st.execute("INSERT INTO organizations VALUES ('" + org + "', 'Org', 'org-" + org.toString().substring(0, 8)
                            + "', true, now(), now())");
                    st.execute("INSERT INTO audit_events VALUES (gen_random_uuid(), '" + org + "', gen_random_uuid(), NULL, "
                            + "'client', 'x', 'created', 'registration', 'information', '{}', now(), gen_random_uuid())");
                }
            }
            var dbx = new Db(db.dataSource());
            int visible = dbx.tenantRead(orgA, c -> {
                try (var st = c.prepareStatement("SELECT count(*) FROM audit_events"); var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            });
            assertThat(visible).isEqualTo(1);
            assertThatThrownBy(() -> dbx.tenant(orgA, Db.Isolation.READ_COMMITTED, c -> {
                try (var st = c.prepareStatement("INSERT INTO audit_events VALUES (gen_random_uuid(), ?, gen_random_uuid(), "
                        + "NULL, 'client', 'x', 'created', 'registration', 'information', '{}', now(), gen_random_uuid())")) {
                    st.setObject(1, orgB);
                    return st.executeUpdate();
                }
            })).isInstanceOf(Db.DatabaseException.class);
            assertThatThrownBy(() -> dbx.tenant(orgA, Db.Isolation.READ_COMMITTED, c -> {
                try (var st = c.prepareStatement("UPDATE audit_events SET \"Action\" = 'forjado'")) {
                    return st.executeUpdate();
                }
            })).isInstanceOf(Db.DatabaseException.class).cause().isInstanceOf(SQLException.class)
                    .hasMessageContaining("somente inclusões");
        }
    }
}
