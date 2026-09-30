package br.com.contadoresassociados.folhas.server.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transações JDBC explícitas do servidor.
 *
 * <p>Pendência 3.2: conflitos de serialização ({@code 40001}) e deadlocks ({@code 40P01}) são
 * repetidos com backoff em vez de virar 500 ou a falsa mensagem de "valor único". Violação de
 * unicidade é identificada só pelo SQLSTATE {@code 23505}.
 *
 * <p>Pendência 3.6: toda transação de requisição autenticada define
 * {@code folhas.organization_id}; as políticas de RLS da migração V3 restringem as linhas a essa
 * organização mesmo que uma consulta esqueça o filtro.
 */
public final class Db {

    private static final Logger LOG = LoggerFactory.getLogger(Db.class);
    public static final int MAX_ATTEMPTS = 5;

    public enum Isolation {
        READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
        REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
        SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

        private final int level;

        Isolation(int level) {
            this.level = level;
        }
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    /** Falha de banco não recuperável, sem detalhes internos para o cliente. */
    public static final class DatabaseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String sqlState;

        public DatabaseException(String message, SQLException cause) {
            super(message, cause);
            this.sqlState = cause == null ? null : cause.getSQLState();
        }

        public String sqlState() {
            return sqlState;
        }

        public boolean isUniqueViolation() {
            return "23505".equals(sqlState);
        }

        public boolean isForeignKeyViolation() {
            return "23503".equals(sqlState);
        }
    }

    private final DataSource dataSource;

    public Db(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /** Transação de uma organização (RLS ativo). */
    public <T> T tenant(UUID organizationId, Isolation isolation, Work<T> work) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId");
        }
        return run(organizationId, isolation, false, false, work);
    }

    /**
     * Executa o trabalho completo e desfaz tudo no final (pendência 3.5: a simulação da importação
     * passa pelas mesmas restrições do banco — unicidade, FKs — sem gravar nada).
     */
    public <T> T tenantDryRun(UUID organizationId, Isolation isolation, Work<T> work) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId");
        }
        return run(organizationId, isolation, false, true, work);
    }

    /** Leitura de uma organização (READ COMMITTED, somente leitura). */
    public <T> T tenantRead(UUID organizationId, Work<T> work) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId");
        }
        return run(organizationId, Isolation.READ_COMMITTED, true, false, work);
    }

    /** Transação de sistema (login, provisionamento): sem escopo de organização. */
    public <T> T system(Isolation isolation, Work<T> work) {
        return run(null, isolation, false, false, work);
    }

    private <T> T run(UUID organizationId, Isolation isolation, boolean readOnly, boolean rollbackOnly, Work<T> work) {
        SQLException last = null;
        for (var attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(isolation.level);
                connection.setReadOnly(readOnly);
                try {
                    try (var st = connection.prepareStatement("SELECT set_config('folhas.organization_id', ?, true)")) {
                        st.setString(1, organizationId == null ? "" : organizationId.toString());
                        st.execute();
                    }
                    var result = work.run(connection);
                    if (rollbackOnly) {
                        try (var st = connection.prepareStatement("SET CONSTRAINTS ALL IMMEDIATE")) {
                            st.execute();
                        }
                        connection.rollback();
                    } else {
                        connection.commit();
                    }
                    return result;
                } catch (SQLException | RuntimeException e) {
                    rollbackQuietly(connection);
                    throw e;
                } finally {
                    connection.setReadOnly(false);
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                if (isRetryable(e) && attempt < MAX_ATTEMPTS) {
                    last = e;
                    LOG.debug("Transação repetida após {} (tentativa {}).", e.getSQLState(), attempt);
                    backoff(attempt);
                    continue;
                }
                throw new DatabaseException("Falha ao acessar o banco central.", e);
            } catch (RetryableWrapper e) {
                if (attempt < MAX_ATTEMPTS) {
                    last = e.cause;
                    backoff(attempt);
                    continue;
                }
                throw new DatabaseException("Falha ao acessar o banco central.", e.cause);
            }
        }
        throw new DatabaseException("O banco central permaneceu em conflito após várias tentativas.", last);
    }

    /** Permite que o trabalho sinalize um conflito recuperável detectado dentro de código não JDBC. */
    public static RuntimeException retry(SQLException cause) {
        return new RetryableWrapper(cause);
    }

    private static final class RetryableWrapper extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient SQLException cause;

        RetryableWrapper(SQLException cause) {
            super(cause);
            this.cause = cause;
        }
    }

    public static boolean isRetryable(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isUniqueViolation(SQLException e) {
        return "23505".equals(e.getSQLState());
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // a conexão será descartada pelo pool
        }
    }

    private static void backoff(int attempt) {
        var base = Math.min(400, 20L << attempt);
        try {
            Thread.sleep(base + ThreadLocalRandom.current().nextLong(base + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido durante nova tentativa de transação.", e);
        }
    }
}
