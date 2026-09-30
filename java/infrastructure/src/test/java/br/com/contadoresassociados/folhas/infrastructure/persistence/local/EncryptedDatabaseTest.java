package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.preferences.WorkspacePreferences;
import br.com.contadoresassociados.folhas.infrastructure.security.InMemorySecretStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedDatabaseTest {

    static final String KEY = "a".repeat(10) + "B-c_d".repeat(6) + "0123456789";

    @TempDir
    Path dir;

    static boolean containsPlain(Path file, String text) throws Exception {
        return Files.exists(file) && new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(text);
    }

    @Test
    void newDatabaseIsEncryptedAndWrongKeyFails() throws Exception {
        var file = dir.resolve("cache.db");
        try (var db = LocalDatabase.open(file, KEY)) {
            db.transaction(c -> c.createStatement().executeUpdate(
                    "INSERT INTO workspace_preferences (\"Key\", \"JsonPayload\", \"UpdatedAtUtc\") VALUES ('k', "
                            + "'{\"cpf\":\"52998224725\"}', 0)"));
        }
        assertThat(LocalDatabase.isPlaintextSqlite(file)).isFalse();
        assertThat(containsPlain(file, "52998224725")).isFalse();
        assertThat(containsPlain(dir.resolve("cache.db-wal"), "52998224725")).isFalse();
        assertThatThrownBy(() -> LocalDatabase.open(file, "x".repeat(40)))
                .isInstanceOf(LocalDatabase.LocalStorageException.class);
        try (var db = LocalDatabase.open(file, KEY)) {
            String payload = db.read(c -> {
                try (var rs = c.createStatement().executeQuery("SELECT \"JsonPayload\" FROM workspace_preferences")) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            assertThat(payload).contains("52998224725");
        }
    }

    @Test
    void plaintextDotNetDatabaseIsMigratedInPlaceKeepingData() throws Exception {
        var file = dir.resolve("cache.db");
        try (var db = LocalDatabase.open(file)) {
            new SqliteKeyValueStores.Preferences(db, Clock.system()).save(new WorkspacePreferences("C:/entrada", null, null,
                    false, 2026, 9, false, 12, "stable", null));
            db.transaction(c -> c.createStatement().executeUpdate(
                    "INSERT INTO workspace_preferences (\"Key\", \"JsonPayload\", \"UpdatedAtUtc\") VALUES ('pii', "
                            + "'Maria da Silva 52998224725', 0)"));
        }
        assertThat(LocalDatabase.isPlaintextSqlite(file)).isTrue();
        var vault = new InMemorySecretStore();
        var opened = LocalDatabaseKeys.openEncrypted(file, vault);
        try (var db = opened.database()) {
            assertThat(opened.recoveredFromLostKey()).isFalse();
            assertThat(new SqliteKeyValueStores.Preferences(db, Clock.system()).load()).isPresent();
        }
        assertThat(LocalDatabase.isPlaintextSqlite(file)).isFalse();
        assertThat(containsPlain(file, "52998224725")).isFalse();
        assertThat(dir.resolve("cache.db.plain-backup")).doesNotExist();
        assertThat(vault.retrieve(LocalDatabaseKeys.KEY)).isPresent();
        // reabrir com a mesma chave do cofre
        try (var db = LocalDatabaseKeys.openEncrypted(file, vault).database()) {
            int migrations = db.read(c -> c.createStatement()
                    .executeQuery("SELECT count(*) FROM \"__EFMigrationsHistory\"").getInt(1));
            assertThat(migrations).isEqualTo(7);
        }
    }

    @Test
    void lostKeyQuarantinesUnreadableDatabaseInsteadOfDeleting() throws Exception {
        var file = dir.resolve("cache.db");
        LocalDatabase.open(file, KEY).close();
        var opened = LocalDatabaseKeys.openEncrypted(file, new InMemorySecretStore());
        opened.database().close();
        assertThat(opened.recoveredFromLostKey()).isTrue();
        assertThat(opened.quarantinedFile()).exists();
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + opened.quarantinedFile()
                + "?cipher=sqlcipher&legacy=4&key=" + KEY)) {
            assertThat(c.createStatement().executeQuery("SELECT count(*) FROM sqlite_master").getInt(1)).isPositive();
        }
    }
}
