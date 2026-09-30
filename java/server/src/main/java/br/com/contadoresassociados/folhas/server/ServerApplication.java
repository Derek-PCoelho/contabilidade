package br.com.contadoresassociados.folhas.server;

import br.com.contadoresassociados.folhas.server.config.Provisioner;
import java.util.Arrays;
import java.util.HashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;

/**
 * Servidor central. {@code java -jar folhas-server.jar} sobe a API; o subcomando
 * {@code provision} prepara o banco (pendência 4.5):
 *
 * <pre>
 * java -jar folhas-server.jar provision --org-name="Contadores Associados" --org-slug=contadores \
 *      --admin-email=ti@exemplo.com.br --admin-name="Equipe técnica"   (senha em FOLHAS_ADMIN_PASSWORD)
 * </pre>
 */
@SpringBootApplication(exclude = FlywayAutoConfiguration.class)
public class ServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "provision".equals(args[0])) {
            provision(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        SpringApplication.run(ServerApplication.class, args);
    }

    private static void provision(String[] args) {
        var options = new HashMap<String, String>();
        for (var arg : args) {
            if (arg.startsWith("--") && arg.contains("=")) {
                options.put(arg.substring(2, arg.indexOf('=')), arg.substring(arg.indexOf('=') + 1));
            }
        }
        var url = env("FOLHAS_DB_URL", options.get("db-url"));
        var user = env("FOLHAS_DB_USER", options.get("db-user"));
        var password = System.getenv("FOLHAS_DB_PASSWORD");
        if (url == null) {
            throw new IllegalArgumentException("Informe FOLHAS_DB_URL (jdbc:postgresql://...).");
        }
        var dataSource = new org.postgresql.ds.PGSimpleDataSource();
        dataSource.setUrl(url);
        if (user != null) {
            dataSource.setUser(user);
        }
        if (password != null) {
            dataSource.setPassword(password);
        }
        Provisioner.FirstAdmin admin = null;
        if (options.containsKey("admin-email")) {
            var adminPassword = System.getenv("FOLHAS_ADMIN_PASSWORD");
            if (adminPassword == null) {
                throw new IllegalArgumentException("Defina a senha inicial em FOLHAS_ADMIN_PASSWORD (nunca na linha de comando).");
            }
            admin = new Provisioner.FirstAdmin(options.getOrDefault("org-name", "Organização"),
                    options.getOrDefault("org-slug", "organizacao"), options.get("admin-email"),
                    options.getOrDefault("admin-name", "Administração"), adminPassword);
        }
        new Provisioner(dataSource).run(admin);
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
