package br.com.contadoresassociados.folhas.server;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Banco PostgreSQL descartável por classe de teste. Usa um papel sem superusuário, dono do banco,
 * para que o RLS ({@code FORCE ROW LEVEL SECURITY}) valha nos testes como em produção.
 */
public final class TestDatabase implements AutoCloseable {

    public static final String APP_ROLE = "folhas_app_test";
    private static final String ADMIN_URL = System.getProperty("folhas.test.pg.url",
            "jdbc:postgresql://127.0.0.1:55432/postgres");
    private static final String ADMIN_USER = System.getProperty("folhas.test.pg.user", "postgres");
    private static final String ADMIN_PASSWORD = System.getProperty("folhas.test.pg.password", "");

    private final String name;
    private final String url;

    private TestDatabase(String name, String url) {
        this.name = name;
        this.url = url;
    }

    public static TestDatabase create() {
        var name = "folhas_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
        try (var c = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD); var st = c.createStatement()) {
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                    + "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD 'test' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
            st.execute("CREATE DATABASE " + name + " OWNER " + APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL de teste indisponível em " + ADMIN_URL, e);
        }
        // extensões exigem superusuário; em produção o provisionamento roda como dono do banco com
        // permissão, aqui são criadas antes pelo administrador
        try (var c = DriverManager.getConnection(ADMIN_URL.replace("/postgres", "/" + name), ADMIN_USER, ADMIN_PASSWORD);
                var st = c.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            st.execute("GRANT ALL ON SCHEMA public TO " + APP_ROLE);
            st.execute("ALTER SCHEMA public OWNER TO " + APP_ROLE);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return new TestDatabase(name, ADMIN_URL.replace("/postgres", "/" + name));
    }

    public String url() {
        return url;
    }

    public String user() {
        return APP_ROLE;
    }

    public String password() {
        return "test";
    }

    public DataSource dataSource() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(APP_ROLE);
        ds.setPassword("test");
        return ds;
    }

    /** Conexão de superusuário (ignora RLS), para inspecionar o banco nos testes. */
    public java.sql.Connection admin() throws SQLException {
        return DriverManager.getConnection(url, ADMIN_USER, ADMIN_PASSWORD);
    }

    @Override
    public void close() {
        try (var c = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PASSWORD); var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException e) {
            // melhor esforço
        }
    }
}
