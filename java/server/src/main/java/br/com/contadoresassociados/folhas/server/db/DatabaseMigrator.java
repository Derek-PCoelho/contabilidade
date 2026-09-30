package br.com.contadoresassociados.folhas.server.db;

import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Migrações Flyway compatíveis com o banco criado pela versão .NET.
 *
 * <p>Banco novo: V1 cria o esquema idêntico ao EF Core. Banco existente da versão .NET: a
 * presença de {@code __EFMigrationsHistory} com a última migration conhecida faz o Flyway
 * registrar o baseline na versão 1 e aplicar apenas as correções (V2+). Um banco com migrations
 * EF desconhecidas é recusado, para não aplicar correções sobre um esquema diferente.
 *
 * <p>Pendência 3.10: somente PostgreSQL é aceito (o SQLite de desenvolvimento do .NET deixava
 * produção rodar sem as garantias de concorrência).
 */
public final class DatabaseMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMigrator.class);
    public static final String LAST_EF_MIGRATION = "20260822150641_AddPartnerOptionalEmail";
    public static final int MINIMUM_POSTGRES_MAJOR = 15;

    private DatabaseMigrator() {
    }

    public static void migrate(DataSource dataSource) {
        var efState = inspect(dataSource);
        var flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineDescription("EF Core schema (" + LAST_EF_MIGRATION + ")")
                .baselineOnMigrate(efState == EfState.CURRENT)
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        var result = flyway.migrate();
        LOG.info("Banco central na versão {} ({} migrações aplicadas).", result.targetSchemaVersion,
                result.migrationsExecuted);
    }

    enum EfState { NONE, CURRENT }

    static EfState inspect(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            if (!meta.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")) {
                throw new IllegalStateException("O banco central precisa ser PostgreSQL.");
            }
            if (meta.getDatabaseMajorVersion() < MINIMUM_POSTGRES_MAJOR) {
                throw new IllegalStateException("O banco central precisa ser PostgreSQL " + MINIMUM_POSTGRES_MAJOR
                        + " ou mais recente (índices NULLS NOT DISTINCT).");
            }
            boolean hasEf;
            boolean hasFlyway;
            try (var st = connection.prepareStatement(
                    "SELECT to_regclass('\"__EFMigrationsHistory\"') IS NOT NULL, to_regclass('flyway_schema_history') IS NOT NULL");
                    var rs = st.executeQuery()) {
                rs.next();
                hasEf = rs.getBoolean(1);
                hasFlyway = rs.getBoolean(2);
            }
            if (!hasEf || hasFlyway) {
                return EfState.NONE;
            }
            String last = null;
            try (var st = connection.prepareStatement(
                    "SELECT \"MigrationId\" FROM \"__EFMigrationsHistory\" ORDER BY \"MigrationId\" DESC LIMIT 1");
                    var rs = st.executeQuery()) {
                if (rs.next()) {
                    last = rs.getString(1);
                }
            }
            if (!LAST_EF_MIGRATION.equals(last)) {
                throw new IllegalStateException("O banco foi criado por uma versão .NET com migrations diferentes ("
                        + last + "). Atualize-o primeiro até " + LAST_EF_MIGRATION + ".");
            }
            return EfState.CURRENT;
        } catch (SQLException e) {
            throw new IllegalStateException("Não foi possível inspecionar o banco central.", e);
        }
    }
}
