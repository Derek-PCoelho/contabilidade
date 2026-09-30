package br.com.contadoresassociados.folhas.infrastructure.updates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.updates.AppUpdates.AppUpdateException;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.Channel;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.State;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignedUpdateTest {

    static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @TempDir
    Path dir;

    HttpServer server;
    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    String privateKey;
    String publicKey;
    final Map<String, Long> sequences = new HashMap<>();
    final List<String> installed = new ArrayList<>();
    boolean platformSignatureValid = true;

    @BeforeAll
    static void allowLoopback() {
        System.setProperty("folhas.test.allowLoopbackProviders", "true");
    }

    @BeforeEach
    void start() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            var body = files.get(ex.getRequestURI().getPath().substring(1));
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    SignedFeedAppUpdateService service(String key) {
        var installer = new SignedFeedAppUpdateService.Installer() {
            @Override
            public void verifyPlatformSignature(Path packageFile) {
                if (!platformSignatureValid) {
                    throw new IllegalStateException("assinatura do SO inválida");
                }
            }

            @Override
            public void installAndRestart(Path packageFile) {
                installed.add(packageFile.getFileName().toString());
            }
        };
        var store = new SignedFeedAppUpdateService.SequenceStore() {
            @Override
            public long lastAccepted(String channelKey) {
                return sequences.getOrDefault(channelKey, 0L);
            }

            @Override
            public void accept(String channelKey, long sequence) {
                sequences.merge(channelKey, sequence, Math::max);
            }
        };
        return new SignedFeedAppUpdateService(new SignedFeedAppUpdateService.Options(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed"), List.of(key),
                dir.resolve("updates"), 10_000_000, Duration.ofSeconds(10)), null, "win-x64", installer, store,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    void publish(String version, long sequence, byte[] pkg, String declaredSha, OffsetDateTime expires, boolean sign)
            throws Exception {
        files.put("feed/folhas-" + version + ".msi", pkg);
        var manifest = new ReleaseManifest(1, "win-x64-stable", sequence, NOW.atOffset(ZoneOffset.UTC), expires, version,
                "1.0.0", "notas", List.of(new ReleaseManifest.Artifact("win-x64", "folhas-" + version + ".msi",
                        declaredSha, pkg.length, "msi")));
        var bytes = Json.writeBytes(manifest);
        files.put("feed/releases-win-x64-stable.json", bytes);
        files.put("feed/releases-win-x64-stable.json.sig",
                (sign ? ReleaseSigner.sign(bytes, privateKey) : "AAAA").getBytes(StandardCharsets.US_ASCII));
    }

    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void validSignedReleaseDownloadsVerifiesAndInstalls() throws Exception {
        var pkg = "pacote-msi".repeat(1000).getBytes(StandardCharsets.UTF_8);
        publish("1.2.0", 5, pkg, sha(pkg), NOW.plusSeconds(86400).atOffset(ZoneOffset.UTC), true);
        var svc = service(publicKey);
        var check = svc.check(Channel.STABLE);
        assertThat(check.state()).isEqualTo(State.AVAILABLE);
        assertThat(check.latestVersion()).isEqualTo("1.2.0");
        var progress = new ArrayList<Integer>();
        var ready = svc.download(progress::add);
        assertThat(ready.state()).isEqualTo(State.READY_TO_RESTART);
        assertThat(progress).last().isEqualTo(100);
        assertThat(Files.readAllBytes(dir.resolve("updates/folhas-1.2.0.msi"))).isEqualTo(pkg);
        svc.applyAndRestart();
        assertThat(installed).containsExactly("folhas-1.2.0.msi");
        assertThat(sequences).containsEntry("win-x64-stable", 5L);
    }

    @Test
    void rejectsUnsignedForeignKeyTamperedAndReplayedManifests() throws Exception {
        var pkg = new byte[] {1, 2, 3};
        var expires = NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC);
        publish("1.2.0", 5, pkg, sha(pkg), expires, false);
        assertThat(service(publicKey).check(Channel.STABLE).message()).contains("assinatura válida");

        publish("1.2.0", 5, pkg, sha(pkg), expires, true);
        var other = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                .getPublic().getEncoded());
        assertThat(service(other).check(Channel.STABLE).state()).isEqualTo(State.FAILED);

        var json = new String(files.get("feed/releases-win-x64-stable.json"), StandardCharsets.UTF_8);
        files.put("feed/releases-win-x64-stable.json", json.replace("1.2.0", "9.9.9").getBytes(StandardCharsets.UTF_8));
        assertThat(service(publicKey).check(Channel.STABLE).state()).isEqualTo(State.FAILED);

        sequences.put("win-x64-stable", 10L);
        publish("1.2.0", 5, pkg, sha(pkg), expires, true);
        assertThat(service(publicKey).check(Channel.STABLE).message()).contains("mais antigo");

        sequences.clear();
        publish("1.2.0", 11, pkg, sha(pkg), NOW.minusSeconds(1).atOffset(ZoneOffset.UTC), true);
        assertThat(service(publicKey).check(Channel.STABLE).message()).contains("expirou");

        publish("0.9.0", 12, pkg, sha(pkg), expires, true);
        assertThat(service(publicKey).check(Channel.STABLE).message()).contains("rebaixar");

        publish("1.0.0", 13, pkg, sha(pkg), expires, true);
        assertThat(service(publicKey).check(Channel.STABLE).state()).isEqualTo(State.UP_TO_DATE);
    }

    @Test
    void rejectsPackageWithWrongHashOrPlatformSignature() throws Exception {
        var pkg = "conteudo".getBytes(StandardCharsets.UTF_8);
        publish("1.3.0", 1, pkg, sha("outro".getBytes(StandardCharsets.UTF_8)), NOW.plusSeconds(3600)
                .atOffset(ZoneOffset.UTC), true);
        var svc = service(publicKey);
        assertThat(svc.check(Channel.STABLE).state()).isEqualTo(State.AVAILABLE);
        assertThatThrownBy(() -> svc.download(null)).isInstanceOf(AppUpdateException.class)
                .hasMessageContaining("alterado ou está corrompido");
        assertThat(dir.resolve("updates/folhas-1.3.0.msi")).doesNotExist();
        assertThatThrownBy(svc::applyAndRestart).hasMessageContaining("Baixe e confira");

        publish("1.3.0", 2, pkg, sha(pkg), NOW.plusSeconds(3600).atOffset(ZoneOffset.UTC), true);
        platformSignatureValid = false;
        var svc2 = service(publicKey);
        svc2.check(Channel.STABLE);
        assertThatThrownBy(() -> svc2.download(null)).hasMessageContaining("publicador esperado");
        assertThat(installed).isEmpty();
    }

    @Test
    void feedWithoutKeyOrHttpsIsUnavailable() {
        var noKey = new SignedFeedAppUpdateService(new SignedFeedAppUpdateService.Options(URI.create("https://x/feed"),
                List.of(), dir, 0, null), null, "win-x64", null, null, null);
        assertThat(noKey.check(Channel.STABLE).message()).contains("chave de publicação");
        var http = new SignedFeedAppUpdateService(new SignedFeedAppUpdateService.Options(URI.create("http://x.com/feed"),
                List.of(publicKey), dir, 0, null), null, "win-x64", null, null, null);
        assertThat(http.check(Channel.STABLE).message()).contains("HTTPS");
    }

    @Test
    void platformInstallersParseOsSignatureOutput() {
        var win = new PlatformInstallers.Windows("Contadores Associados Ltda",
                (cmd, t) -> new PlatformInstallers.CommandRunner.Result(0,
                        "Valid|CN=Contadores Associados Ltda, O=Contadores Associados Ltda, C=BR"), () -> { });
        win.verifyPlatformSignature(Path.of("x.msi"));
        var fake = new PlatformInstallers.Windows("Contadores Associados Ltda",
                (cmd, t) -> new PlatformInstallers.CommandRunner.Result(0,
                        "Valid|CN=Contadores Associados Ltda Falso, C=BR"), () -> { });
        assertThatThrownBy(() -> fake.verifyPlatformSignature(Path.of("x.msi"))).isInstanceOf(IllegalStateException.class);

        var outputs = new HashMap<String, String>();
        outputs.put("/usr/sbin/pkgutil", "Status: signed by a developer certificate issued by Apple for distribution\n"
                + "1. Developer ID Installer: Contadores (ABCDE12345)");
        outputs.put("/usr/sbin/spctl", "x.pkg: accepted\nsource=Notarized Developer ID");
        var mac = new PlatformInstallers.MacOs("ABCDE12345",
                (cmd, t) -> new PlatformInstallers.CommandRunner.Result(0, outputs.get(cmd.getFirst())), () -> { });
        mac.verifyPlatformSignature(Path.of("x.pkg"));
        outputs.put("/usr/sbin/spctl", "x.pkg: rejected");
        assertThatThrownBy(() -> mac.verifyPlatformSignature(Path.of("x.pkg"))).hasMessageContaining("Gatekeeper");
    }
}
