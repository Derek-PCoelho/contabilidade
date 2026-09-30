package br.com.contadoresassociados.folhas.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.json.Json;
import br.com.contadoresassociados.folhas.server.ServerApplication;
import br.com.contadoresassociados.folhas.server.TestDatabase;
import br.com.contadoresassociados.folhas.server.config.Provisioner;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Servidor real em porta aleatória, PostgreSQL real, autenticação de desenvolvimento (Testing). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest {

    static final String ALL = "clients.read,clients.write,clients.export,templates.read,templates.write,email.send,users.manage";
    final UUID org = UUID.randomUUID();
    final UUID otherOrg = UUID.randomUUID();
    final UUID user = UUID.randomUUID();
    final UUID device = UUID.randomUUID();
    TestDatabase db;
    ConfigurableApplicationContext context;
    String base;
    final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll
    void start() throws Exception {
        db = TestDatabase.create();
        new Provisioner(db.dataSource()).run(null);
        try (var c = db.admin(); var st = c.createStatement()) {
            for (var o : new UUID[] {org, otherOrg}) {
                st.execute("INSERT INTO organizations VALUES ('" + o + "', 'Org', 'org-" + o.toString().substring(0, 8)
                        + "', true, now(), now())");
            }
            st.execute("INSERT INTO users (\"Id\", \"OrganizationId\", \"DisplayName\", \"IsActive\", \"Version\", "
                    + "\"EmailConfirmed\", \"PhoneNumberConfirmed\", \"TwoFactorEnabled\", \"LockoutEnabled\", "
                    + "\"AccessFailedCount\") VALUES ('" + user + "', '" + org + "', 'Teste', true, 1, true, false, false, true, 0)");
            st.execute("INSERT INTO device_sessions VALUES ('" + device + "', '" + org + "', '" + user
                    + "', 'Teste', now(), now(), NULL)");
        }
        var app = new SpringApplication(ServerApplication.class);
        context = app.run("--server.port=0", "--spring.datasource.url=" + db.url(),
                "--spring.datasource.username=" + db.user(), "--spring.datasource.password=" + db.password(),
                "--folhas.environment=Testing", "--folhas.authentication.enable-development-scheme=true",
                "--folhas.oidc.issuer=http://localhost", "--folhas.phase10.email-send-enabled=true",
                "--folhas.phase7.microsoft-graph.email-send-enabled=true", "--folhas.phase7.minimum-send-version=0.1.0",
                "--folhas.phase10.minimum-supported-version=0.1.0");
        base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
        if (db != null) {
            db.close();
        }
    }

    HttpResponse<String> call(String method, String path, Object body, UUID organization, String permissions,
            boolean mfa) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("X-Development-User", user.toString())
                .header("X-Development-Organization", organization.toString())
                .header("X-Development-Device", device.toString())
                .header("X-Development-Permissions", permissions)
                .header("X-Development-Roles", "Manager")
                .header("X-Development-Mfa", Boolean.toString(mfa))
                .header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(Json.write(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return call(method, path, body, org, ALL, true);
    }

    static JsonNode json(HttpResponse<String> r) throws Exception {
        return Json.mapper().readTree(r.body());
    }

    static Map<String, Object> client(String name, String cnpj, String email) {
        var m = new LinkedHashMap<String, Object>();
        m.put("expectedVersion", 0);
        m.put("personType", 1);
        m.put("legalNameOrFullName", name);
        m.put("primaryTaxId", cnpj);
        m.put("isActive", true);
        m.put("recipients", java.util.List.of(Map.of("id", UUID.randomUUID().toString(), "displayName", "Financeiro",
                "email", email, "deliveryRole", 0, "isPrimary", true, "isActive", true)));
        return m;
    }

    @Test
    void anonymousAndHeadersAreEnforced() throws Exception {
        var anon = http.send(HttpRequest.newBuilder(URI.create(base + "/api/clients")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(anon.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(anon.headers().firstValue("X-Correlation-ID")).isPresent();
        assertThat(json(anon).path("code").asText()).isEqualTo("authentication_required");
        var status = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json(status).path("service").asText()).isEqualTo("Folhas da Michelly API");
        var ready = http.send(HttpRequest.newBuilder(URI.create(base + "/health/ready")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(200);
    }

    @Test
    void writesRequireMfaAndPermission() throws Exception {
        var noMfa = call("POST", "/api/clients", client("Sem MFA", "11222333000181", "a@exemplo.com.br"), org, ALL, false);
        assertThat(noMfa.statusCode()).isEqualTo(403);
        assertThat(json(noMfa).path("code").asText()).isEqualTo("mfa_required");
        var noPermission = call("GET", "/api/clients", null, org, "templates.read", true);
        assertThat(noPermission.statusCode()).isEqualTo(403);
        var export = call("GET", "/api/clients/catalog/export", null, org, "clients.read,templates.read", true);
        assertThat(export.statusCode()).as("4.13: export exige clients.export").isEqualTo(403);
    }

    @Test
    void clientLifecycleWithBatchStatusArchiveAndTenantIsolation() throws Exception {
        var created = call("POST", "/api/clients", client("Padaria São João Ltda", "04252011000110", "fin@padaria.com.br"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var node = json(created);
        var id = node.path("id").asText();
        assertThat(node.path("version").asLong()).isEqualTo(1);

        var duplicate = call("POST", "/api/clients", client("Outra", "04252011000110", "x@padaria.com.br"));
        assertThat(duplicate.statusCode()).isEqualTo(400);
        assertThat(json(duplicate).path("code").asText()).isEqualTo("catalog.duplicate");

        var search = json(call("GET", "/api/clients?search=sao%20joao", null));
        assertThat(search.path("total").asInt()).as("3.8: busca sem acento").isEqualTo(1);
        assertThat(search.path("items").get(0).path("primaryTaxIdMasked").asText()).doesNotContain("04252011000110");

        var batch = json(call("POST", "/api/clients/batch", Map.of("clientIds", java.util.List.of(id, UUID.randomUUID()))));
        assertThat(batch.size()).isEqualTo(1);

        var readiness = json(call("GET", "/api/clients/" + id + "/readiness", null));
        assertThat(readiness.path("isEligible").asBoolean()).isTrue();

        var otherTenant = call("GET", "/api/clients/" + id, null, otherOrg, ALL, true);
        assertThat(otherTenant.statusCode()).isEqualTo(404);

        var stale = call("POST", "/api/clients/" + id + "/status", Map.of("expectedVersion", 7, "isActive", false));
        assertThat(stale.statusCode()).isEqualTo(409);
        var archiveActive = call("POST", "/api/clients/" + id + "/archive", Map.of("expectedVersion", 1));
        assertThat(archiveActive.statusCode()).isEqualTo(400);
        var deactivated = json(call("POST", "/api/clients/" + id + "/status", Map.of("expectedVersion", 1, "isActive", false)));
        assertThat(deactivated.path("isActive").asBoolean()).isFalse();
        var archived = call("POST", "/api/clients/" + id + "/archive", Map.of("expectedVersion", 2, "reason", "encerrado"));
        assertThat(archived.statusCode()).isEqualTo(204);
        assertThat(call("GET", "/api/clients/" + id, null).statusCode()).isEqualTo(404);

        var audit = json(call("GET", "/api/clients/" + id + "/audit", null));
        assertThat(audit.findValuesAsText("action")).contains("created", "deactivated", "archived");
    }

    @Test
    void templatesAndImportDryRunRollsBack() throws Exception {
        var template = new LinkedHashMap<String, Object>();
        template.put("expectedVersion", 0);
        template.put("name", "Padrão folha");
        template.put("subjectTemplate", "Folha {{periodo.rotulo}}");
        template.put("bodyTemplate", "Olá, {{cliente.razao_social}}.");
        template.put("signatureMode", 0);
        template.put("isDefault", true);
        template.put("isActive", true);
        var created = call("POST", "/api/message-templates", template);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        var duplicateName = call("POST", "/api/message-templates", template);
        assertThat(duplicateName.statusCode()).isEqualTo(400);

        var export = call("GET", "/api/clients/catalog/export", null);
        assertThat(export.statusCode()).isEqualTo(200);
        Map<String, Object> document = Json.mapper().readValue(export.body(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        var clients = new java.util.ArrayList<Object>((java.util.List<?>) document.get("clients"));
        var newClient = new LinkedHashMap<String, Object>(client("Importada SA", "11444777000161", "imp@exemplo.com.br"));
        newClient.put("id", UUID.randomUUID().toString());
        newClient.put("version", 1);
        clients.add(newClient);
        var modified = new LinkedHashMap<String, Object>(document);
        modified.put("clients", clients);
        var dry = json(call("POST", "/api/clients/catalog/import", Map.of("dryRun", true, "overwriteExisting", false,
                "document", modified)));
        assertThat(dry.path("clientsCreated").asInt()).isEqualTo(1);
        var after = json(call("GET", "/api/clients?search=Importada", null));
        assertThat(after.path("total").asInt()).as("simulação não grava").isZero();
        var real = json(call("POST", "/api/clients/catalog/import", Map.of("dryRun", false, "overwriteExisting", false,
                "document", modified)));
        assertThat(real.path("clientsCreated").asInt()).isEqualTo(1);
        assertThat(json(call("GET", "/api/clients?search=Importada", null)).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void syncPushIsIdempotentAndPullAdvancesCheckpoint() throws Exception {
        var clientId = UUID.randomUUID();
        var operation = UUID.randomUUID();
        var command = Map.of("operationId", operation.toString(), "clientId", clientId.toString(), "displayName", "Sync",
                "isActive", true, "expectedVersion", 0);
        var first = json(call("POST", "/api/sync/clients", Map.of("commands", java.util.List.of(command))));
        assertThat(first.path("results").get(0).path("status").asInt()).isZero();
        var again = json(call("POST", "/api/sync/clients", Map.of("commands", java.util.List.of(command))));
        assertThat(again.path("results").get(0).path("status").asInt()).isEqualTo(1);
        var pulled = json(call("GET", "/api/sync/clients?checkpoint=0", null));
        assertThat(pulled.path("checkpoint").asLong()).isPositive();
        assertThat(pulled.path("clients").findValuesAsText("displayName")).contains("Sync");
        var empty = call("POST", "/api/sync/clients", Map.of("commands", java.util.List.of()));
        assertThat(empty.statusCode()).isEqualTo(400);
    }

    @Test
    void preflightAuthorizesTestModeAndBatchesWithCodes() throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("operationId", UUID.randomUUID().toString());
        request.put("providerKey", "microsoft.graph");
        request.put("dispatchFingerprint", "a".repeat(64));
        request.put("attachmentCount", 1);
        request.put("applicationVersion", "1.0.0");
        request.put("operationMode", 0);
        request.put("batchSize", 1);
        request.put("attemptNumber", 1);
        var single = json(call("POST", "/api/email-dispatch/preflight", request));
        assertThat(single.path("authorized").asBoolean()).as(single.toString()).isTrue();
        var send = new LinkedHashMap<>(request);
        send.put("operationId", UUID.randomUUID().toString());
        send.put("operationMode", 2);
        var invalid = new LinkedHashMap<>(request);
        invalid.put("dispatchFingerprint", "zz");
        var batch = json(call("POST", "/api/email-dispatch/preflight/batch", Map.of("items", java.util.List.of(send, invalid))));
        assertThat(batch.path("results").get(0).path("errorCode").asText()).isEqualTo("PRODUCTION_ROLLOUT_CLOSED");
        assertThat(batch.path("results").get(1).path("errorCode").asText()).isEqualTo("PREFLIGHT_INVALID_REQUEST");
        var badSingle = call("POST", "/api/email-dispatch/preflight", invalid);
        assertThat(badSingle.statusCode()).isEqualTo(400);
    }

    @Test
    void sessionsCanBeListedAndRevoked() throws Exception {
        var me = json(call("GET", "/api/sessions/me", null));
        assertThat(me.path("deviceSessionId").asText()).isEqualTo(device.toString());
        var list = json(call("GET", "/api/sessions", null));
        assertThat(list.get(0).path("isCurrent").asBoolean()).isTrue();
        var policy = call("GET", "/api/app-release-policy?currentVersion=1.0.0", null);
        assertThat(json(policy).path("isSupported").asBoolean()).isTrue();
        var pilot = call("GET", "/api/pilot/policy", null);
        assertThat(pilot.statusCode()).as("4.18: piloto desligado por padrão").isEqualTo(503);
    }
}
