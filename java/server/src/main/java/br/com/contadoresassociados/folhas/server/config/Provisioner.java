package br.com.contadoresassociados.folhas.server.config;

import br.com.contadoresassociados.folhas.domain.identity.AppRole;
import br.com.contadoresassociados.folhas.domain.identity.RolePermissionCatalog;
import br.com.contadoresassociados.folhas.server.db.DatabaseMigrator;
import br.com.contadoresassociados.folhas.server.db.Db;
import br.com.contadoresassociados.folhas.server.identity.OidcClients;
import br.com.contadoresassociados.folhas.server.identity.UserStore;
import br.com.contadoresassociados.folhas.server.security.PasswordHasher;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provisionamento idempotente (pendência 4.5): migrações, papéis com as claims de permissão,
 * cliente OIDC do Desktop e, opcionalmente, a primeira organização e o primeiro administrador.
 * Roda no pipeline com {@code java -jar folhas-server.jar provision ...}; pode ser repetido.
 */
public final class Provisioner {

    private static final Logger LOG = LoggerFactory.getLogger(Provisioner.class);
    public static final String PERMISSION_CLAIM = "permission";

    public record FirstAdmin(String organizationName, String organizationSlug, String email, String displayName,
            String password) {
    }

    private final Db db;

    public Provisioner(DataSource dataSource) {
        this.db = new Db(dataSource);
    }

    public void run(FirstAdmin admin) {
        DatabaseMigrator.migrate(db.dataSource());
        db.system(Db.Isolation.SERIALIZABLE, c -> {
            seedRoles(c);
            new OidcClients().ensureDesktopClient(c);
            if (admin != null) {
                seedAdmin(c, admin);
            }
            return null;
        });
    }

    static void seedRoles(Connection c) throws SQLException {
        for (var role : AppRole.values()) {
            UUID roleId = null;
            try (var st = c.prepareStatement("SELECT \"Id\" FROM roles WHERE \"NormalizedName\" = ?")) {
                st.setString(1, role.wireName().toUpperCase(Locale.ROOT));
                try (var rs = st.executeQuery()) {
                    if (rs.next()) {
                        roleId = rs.getObject(1, UUID.class);
                    }
                }
            }
            if (roleId == null) {
                roleId = UUID.randomUUID();
                try (var st = c.prepareStatement("INSERT INTO roles (\"Id\", \"Name\", \"NormalizedName\", \"ConcurrencyStamp\") "
                        + "VALUES (?, ?, ?, ?)")) {
                    st.setObject(1, roleId);
                    st.setString(2, role.wireName());
                    st.setString(3, role.wireName().toUpperCase(Locale.ROOT));
                    st.setString(4, UUID.randomUUID().toString());
                    st.executeUpdate();
                }
                LOG.info("Papel {} criado.", role.wireName());
            }
            for (var permission : RolePermissionCatalog.forRole(role)) {
                try (var st = c.prepareStatement("INSERT INTO role_claims (\"RoleId\", \"ClaimType\", \"ClaimValue\") "
                        + "SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM role_claims WHERE \"RoleId\" = ? AND "
                        + "\"ClaimType\" = ? AND \"ClaimValue\" = ?)")) {
                    st.setObject(1, roleId);
                    st.setString(2, PERMISSION_CLAIM);
                    st.setString(3, permission.wireName());
                    st.setObject(4, roleId);
                    st.setString(5, PERMISSION_CLAIM);
                    st.setString(6, permission.wireName());
                    st.executeUpdate();
                }
            }
        }
    }

    static void seedAdmin(Connection c, FirstAdmin admin) throws SQLException {
        var slug = admin.organizationSlug().strip().toLowerCase(Locale.ROOT);
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,78}[a-z0-9]")) {
            throw new IllegalArgumentException("Slug da organização inválido (use letras minúsculas, números e hífens).");
        }
        UUID organizationId = null;
        try (var st = c.prepareStatement("SELECT \"Id\" FROM organizations WHERE \"Slug\" = ?")) {
            st.setString(1, slug);
            try (var rs = st.executeQuery()) {
                if (rs.next()) {
                    organizationId = rs.getObject(1, UUID.class);
                }
            }
        }
        if (organizationId == null) {
            organizationId = UUID.randomUUID();
            try (var st = c.prepareStatement("INSERT INTO organizations (\"Id\", \"Name\", \"Slug\", \"IsActive\", "
                    + "\"CreatedAtUtc\", \"UpdatedAtUtc\") VALUES (?, ?, ?, true, ?, ?)")) {
                var now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC);
                st.setObject(1, organizationId);
                st.setString(2, admin.organizationName().strip());
                st.setString(3, slug);
                st.setObject(4, now);
                st.setObject(5, now);
                st.executeUpdate();
            }
            LOG.info("Organização {} criada.", slug);
        }
        var users = new UserStore();
        if (users.findByEmail(c, admin.email()).isPresent()) {
            LOG.info("Administrador {} já existe; nada a fazer.", mask(admin.email()));
            return;
        }
        var problems = PasswordHasher.validatePolicy(admin.password());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Senha inicial fraca: " + String.join(" ", problems));
        }
        users.create(c, organizationId, admin.email(), admin.displayName(), new PasswordHasher().hash(admin.password()),
                List.of(AppRole.OWNER_TECHNICAL.wireName()));
        LOG.info("Administrador {} criado; o segundo fator será cadastrado no primeiro login ({}).", mask(admin.email()),
                Instant.now());
    }

    private static String mask(String email) {
        var at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
