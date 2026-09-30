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
 * WAL, {@code busy_timeout}, {@code foreign_keys} e {@code secure_delete} ativos; permissões
 * 0700/0600 fora do Windows; cifrado em repouso quando aberto com chave.
 */
public final class LocalDatabase implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Path file;
    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean inTransaction;

    private LocalDatabase(Path file, Connection connection) {
        this.file = file;
        this.connection = connection;
    }

    public static LocalDatabase open(Path file) {
        return open(file, null);
    }

    /**
     * Abre o {@code cache.db}. Com {@code key}, o arquivo fica cifrado em repouso
     * (SQLite3MultipleCiphers, esquema SQLCipher 4 — pendências 3.17/7.3). Um banco em claro
     * gravado pela versão .NET é convertido no lugar na primeira abertura: é copiado para
     * {@code cache.db.plain-backup}, recifrado com {@code PRAGMA rekey}, conferido e só então a cópia
     * em claro é apagada. Chave errada falha com {@link LocalStorageException} sem alterar o arquivo.
     */
    public static LocalDatabase open(Path file, String key) {
        try {
            var dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            if (key != null) {
                requireSafeKey(key);
                if (Files.exists(file) && isPlaintextSqlite(file)) {
                    encryptInPlace(file, key);
                }
            }
            var c = DriverManager.getConnection(url(file, key));
            try (var st = c.createStatement()) {
                // valida a chave antes de qualquer outra coisa
                st.executeQuery("SELECT count(*) FROM sqlite_master").close();
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=5000");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA secure_delete=ON");
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            var db = new LocalDatabase(file, c);
            new LocalSchemaMigrator().migrate(db);
            db.restrictPermissions();
            return db;
        } catch (SQLException e) {
            throw new LocalStorageException(e);
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível abrir o banco local.", e);
        }
    }

    /** Verdadeiro quando o arquivo começa com o cabeçalho de um SQLite não cifrado. */
    public static boolean isPlaintextSqlite(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) < 16) {
            return false;
        }
        try (var in = Files.newInputStream(file)) {
            var header = in.readNBytes(16);
            return new String(header, java.nio.charset.StandardCharsets.US_ASCII).equals("SQLite format 3\0");
        }
    }

    private static void encryptInPlace(Path file, String key) throws IOException, SQLException {
        var backup = Path.of(file.toAbsolutePath() + ".plain-backup");
        // consolida o WAL no arquivo principal antes de copiar/recifrar
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var st = c.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            st.executeQuery("PRAGMA journal_mode=DELETE").close();
        }
        Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (var c = DriverManager.getConnection("jdbc:sqlite:file:" + file.toAbsolutePath()
                + "?cipher=sqlcipher&legacy=4"); var st = c.createStatement()) {
            st.execute("PRAGMA rekey='" + key + "'");
        }
        try (var c = DriverManager.getConnection(url(file, key)); var st = c.createStatement()) {
            st.executeQuery("SELECT count(*) FROM sqlite_master").close();
            try (var rs = st.executeQuery("PRAGMA integrity_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new SQLException("integrity_check falhou após cifrar o banco local.");
                }
            }
        } catch (SQLException e) {
            // restaura o original em claro: nenhum dado se perde
            Files.copy(backup, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            throw e;
        }
        overwriteAndDelete(backup);
        for (var suffix : new String[] {"-wal", "-shm"}) {
            Files.deleteIfExists(Path.of(file.toAbsolutePath() + suffix));
        }
    }

    /** Sobrescreve a cópia em claro antes de apagar (melhor esforço; SSDs podem reter blocos). */
    private static void overwriteAndDelete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        var size = Files.size(path);
        try (var ch = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
            var zeros = java.nio.ByteBuffer.allocate(64 * 1024);
            long written = 0;
            while (written < size) {
                zeros.clear();
                zeros.limit((int) Math.min(zeros.capacity(), size - written));
                written += ch.write(zeros, written);
            }
            ch.force(true);
        }
        Files.delete(path);
    }

    private static String url(Path file, String key) {
        var path = file.toAbsolutePath().toString();
        return key == null ? "jdbc:sqlite:" + path : "jdbc:sqlite:file:" + path + "?cipher=sqlcipher&legacy=4&key=" + key;
    }

    /** A chave vai na URI e num PRAGMA: só base64url (gerada por {@code LocalDatabaseKeys}). */
    private static void requireSafeKey(String key) {
        if (key.length() < 32 || !key.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Chave do banco local inválida.");
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

    /**
     * Executa {@code work} numa transação. Chamadas aninhadas (mesma thread) participam da
     * transação externa: só a mais externa confirma, e qualquer exceção desfaz o conjunto inteiro
     * — usado para gravar o cadastro e revalidar a revisão de forma atômica.
     */
    public <T> T transaction(SqlWork<T> work) {
        lock.lock();
        try {
            if (inTransaction) {
                return work.run(connection);
            }
            inTransaction = true;
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                inTransaction = false;
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
