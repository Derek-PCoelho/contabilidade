package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Banco local ({@code cache.db}) com uma conexão serializada por lock.
 * WAL, {@code busy_timeout} e {@code foreign_keys} ativos; permissões 0700/0600 fora do Windows.
 */
public final class LocalDatabase implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Path file;
    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    private LocalDatabase(Path file, Connection connection) {
        this.file = file;
        this.connection = connection;
    }

    public static LocalDatabase open(Path file) {
        try {
            var dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            var c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (var st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA synchronous=NORMAL");
            }
            var db = new LocalDatabase(file, c);
            new LocalSchemaMigrator().migrate(db);
            db.restrictPermissions();
            return db;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Não foi possível abrir o banco local.", e);
        }
    }

    public static LocalDatabase inMemory() {
        try {
            var db = new LocalDatabase(null, DriverManager.getConnection("jdbc:sqlite::memory:"));
            new LocalSchemaMigrator().migrate(db);
            return db;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public <T> T read(SqlWork<T> work) {
        lock.lock();
        try {
            return work.run(connection);
        } catch (SQLException e) {
            throw new LocalStorageException(e);
        } finally {
            lock.unlock();
        }
    }

    public <T> T transaction(SqlWork<T> work) {
        lock.lock();
        try {
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new LocalStorageException(e);
        } finally {
            lock.unlock();
        }
    }

    private void restrictPermissions() {
        if (file == null || System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return;
        }
        try {
            var dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            }
            for (var suffix : new String[] {"", "-wal", "-shm"}) {
                var p = Path.of(file.toAbsolutePath() + suffix);
                if (Files.exists(p)) {
                    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
                }
            }
        } catch (IOException | UnsupportedOperationException e) {
            // O sistema de arquivos pode não suportar POSIX; a criptografia do volume segue como controle principal.
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public static final class LocalStorageException extends RuntimeException {
        public LocalStorageException(SQLException cause) {
            super("Falha no banco local: " + cause.getMessage(), cause);
        }
    }
}
