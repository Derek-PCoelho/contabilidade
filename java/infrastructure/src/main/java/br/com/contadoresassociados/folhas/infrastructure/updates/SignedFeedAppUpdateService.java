package br.com.contadoresassociados.folhas.infrastructure.updates;

import br.com.contadoresassociados.folhas.application.updates.AppUpdates;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.AppUpdateException;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.Channel;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.Snapshot;
import br.com.contadoresassociados.folhas.application.updates.AppUpdates.State;
import br.com.contadoresassociados.folhas.application.updates.AppVersion;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Atualização por feed HTTPS com manifesto assinado (substitui o Velopack do .NET).
 *
 * <p>Pendência 7.2 (P0): o .NET confiava no hash publicado pelo próprio feed — um feed
 * comprometido publicava um binário malicioso "válido". Agora:
 * <ol>
 *   <li>o manifesto {@code releases-{plataforma}-{canal}.json} precisa de assinatura Ed25519 de uma
 *       chave fixada no app ({@link SignedManifestVerifier});</li>
 *   <li>canal, plataforma e formato do manifesto são conferidos; manifesto expirado ou com
 *       {@code sequence} menor que a última vista é rejeitado (anti-replay), e downgrade é proibido;</li>
 *   <li>o pacote é baixado para um arquivo temporário, com tamanho máximo e SHA-256 conferidos em
 *       tempo constante antes de ser movido para a área de atualização;</li>
 *   <li>a instalação é delegada ao {@link Installer} da plataforma, que também confere a assinatura
 *       do sistema operacional (Authenticode no Windows, codesign/notarização no macOS).</li>
 * </ol>
 */
public final class SignedFeedAppUpdateService implements AppUpdates.Service {

    public record Options(URI feedBaseUrl, java.util.List<String> trustedPublicKeys, Path updatesDirectory,
            long maximumPackageBytes, Duration timeout) {

        public Options {
            maximumPackageBytes = maximumPackageBytes <= 0 ? 600L * 1024 * 1024 : maximumPackageBytes;
            timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
        }
    }

    /** Instalador específico da plataforma (MSI/MSIX no Windows, PKG/DMG no macOS). */
    public interface Installer {
        /** Confere a assinatura do sistema operacional; lança se o pacote não for do publicador esperado. */
        void verifyPlatformSignature(Path packageFile);

        /** Inicia o instalador e encerra o aplicativo atual. */
        void installAndRestart(Path packageFile);
    }

    /** Guarda a maior {@code sequence} aceita por canal (anti-replay). */
    public interface SequenceStore {
        long lastAccepted(String channelKey);

        void accept(String channelKey, long sequence);
    }

    private final Options options;
    private final HttpClient http;
    private final String platform;
    private final SignedManifestVerifier verifier;
    private final Installer installer;
    private final SequenceStore sequences;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private ReleaseManifest pending;
    private ReleaseManifest.Artifact pendingArtifact;
    private Channel activeChannel = Channel.STABLE;
    private Path downloaded;

    public SignedFeedAppUpdateService(Options options, HttpClient http, String platform, Installer installer,
            SequenceStore sequences, Clock clock) {
        this.options = options;
        this.http = http == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build() : http;
        this.platform = platform;
        this.verifier = options.trustedPublicKeys() == null || options.trustedPublicKeys().isEmpty() ? null
                : new SignedManifestVerifier(options.trustedPublicKeys());
        this.installer = installer;
        this.sequences = sequences;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** {@code win-x64}, {@code osx-arm64}... ou {@code null} para plataformas sem canal autorizado. */
    public static String currentPlatform() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64"))) {
            return "win-x64";
        }
        if (os.contains("mac") && (arch.equals("aarch64") || arch.equals("arm64"))) {
            return "osx-arm64";
        }
        return null;
    }

    @Override
    public Snapshot check(Channel channel) {
        lock.lock();
        try {
            pending = null;
            pendingArtifact = null;
            downloaded = null;
            activeChannel = channel;
            var current = AppVersion.CURRENT.toString();
            var feedError = feedError();
            if (feedError != null) {
                return snapshot(State.UNAVAILABLE, current, null, feedError);
            }
            if (platform == null) {
                return snapshot(State.UNAVAILABLE, current, null,
                        "Esta arquitetura ainda não possui um canal de atualização autorizado.");
            }
            if (installer == null) {
                return snapshot(State.NOT_INSTALLED, current, null,
                        "A verificação funciona depois que o aplicativo é instalado por um pacote oficial.");
            }
            var channelKey = platform + "-" + channel.name().toLowerCase(Locale.ROOT);
            var manifestUri = base().resolve("releases-" + channelKey + ".json");
            var manifestBytes = getBytes(manifestUri, 1024 * 1024);
            var signature = new String(getBytes(URI.create(manifestUri + ".sig"), 4096), StandardCharsets.US_ASCII);
            if (!verifier.verify(manifestBytes, signature)) {
                return snapshot(State.FAILED, current, null,
                        "O canal de atualização não apresentou uma assinatura válida do escritório. Nada foi baixado.");
            }
            var manifest = Json.read(manifestBytes, ReleaseManifest.class);
            var problem = validate(manifest, channelKey);
            if (problem != null) {
                return snapshot(State.FAILED, current, null, problem);
            }
            sequences.accept(channelKey, manifest.sequence());
            var latest = AppVersion.parse(manifest.version()).orElseThrow();
            if (latest.compareTo(AppVersion.CURRENT) <= 0) {
                return snapshot(State.UP_TO_DATE, current, manifest.version(),
                        "Esta instalação já está atualizada neste canal.");
            }
            pending = manifest;
            pendingArtifact = manifest.artifacts().stream().filter(a -> platform.equals(a.platform())).findFirst()
                    .orElseThrow();
            return snapshot(State.AVAILABLE, current, manifest.version(),
                    "Há uma atualização verificada disponível. Baixe quando puder reiniciar o aplicativo.");
        } catch (IOException | RuntimeException e) {
            return snapshot(State.FAILED, AppVersion.CURRENT.toString(), null,
                    "Não foi possível consultar atualizações agora. O trabalho local continua disponível.");
        } finally {
            lock.unlock();
        }
    }

    private String validate(ReleaseManifest manifest, String channelKey) {
        if (manifest == null || manifest.formatVersion() != ReleaseManifest.FORMAT_VERSION
                || !channelKey.equals(manifest.channel())) {
            return "O manifesto de atualização não corresponde a este canal.";
        }
        var now = clock.instant();
        if (manifest.expiresAtUtc() == null || !manifest.expiresAtUtc().toInstant().isAfter(now)) {
            return "O manifesto de atualização expirou. Tente novamente mais tarde.";
        }
        if (manifest.sequence() < sequences.lastAccepted(channelKey)) {
            return "O canal apresentou um manifesto mais antigo que o já conhecido e foi rejeitado.";
        }
        var version = AppVersion.parse(manifest.version());
        if (version.isEmpty()) {
            return "O manifesto de atualização tem versão inválida.";
        }
        if (version.get().compareTo(AppVersion.CURRENT) < 0) {
            return "O canal oferece uma versão anterior à instalada; rebaixar não é permitido.";
        }
        var artifact = manifest.artifacts().stream().filter(a -> platform.equals(a.platform())).findFirst();
        if (artifact.isEmpty()) {
            return "Não há pacote desta plataforma no canal.";
        }
        var a = artifact.get();
        if (a.fileName() == null || !a.fileName().matches("[A-Za-z0-9._-]{1,120}") || a.sizeBytes() <= 0
                || a.sizeBytes() > options.maximumPackageBytes() || a.sha256() == null
                || !a.sha256().matches("[0-9a-fA-F]{64}")) {
            return "O pacote descrito no manifesto é inválido.";
        }
        return null;
    }

    @Override
    public Snapshot download(IntConsumer progress) {
        lock.lock();
        try {
            if (pending == null || pendingArtifact == null) {
                throw new AppUpdateException("Verifique se há atualização antes de iniciar o download.", null);
            }
            Files.createDirectories(options.updatesDirectory());
            var target = options.updatesDirectory().resolve(pendingArtifact.fileName());
            var temp = Files.createTempFile(options.updatesDirectory(), "download-", ".part");
            try {
                var request = HttpRequest.newBuilder(base().resolve(pendingArtifact.fileName()))
                        .timeout(options.timeout()).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode());
                }
                var digest = MessageDigest.getInstance("SHA-256");
                long total = 0;
                try (InputStream in = response.body(); var out = Files.newOutputStream(temp)) {
                    var buffer = new byte[64 * 1024];
                    int read;
                    var lastPercent = -1;
                    while ((read = in.read(buffer)) > 0) {
                        total += read;
                        if (total > pendingArtifact.sizeBytes()) {
                            throw new AppUpdateException("O pacote recebido é maior que o anunciado e foi rejeitado.",
                                    null);
                        }
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                        var percent = (int) (total * 100 / pendingArtifact.sizeBytes());
                        if (progress != null && percent != lastPercent) {
                            progress.accept(Math.min(99, percent));
                            lastPercent = percent;
                        }
                    }
                }
                if (total != pendingArtifact.sizeBytes()
                        || !SignedManifestVerifier.matchesSha256(digest.digest(), pendingArtifact.sha256())) {
                    throw new AppUpdateException("O pacote recebido foi alterado ou está corrompido e foi rejeitado.",
                            null);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                try {
                    installer.verifyPlatformSignature(target);
                } catch (RuntimeException e) {
                    Files.deleteIfExists(target);
                    throw new AppUpdateException(
                            "O pacote não tem a assinatura do publicador esperado e foi rejeitado.", e);
                }
                downloaded = target;
                if (progress != null) {
                    progress.accept(100);
                }
                return new Snapshot(State.READY_TO_RESTART, AppVersion.CURRENT.toString(), pending.version(),
                        activeChannel, 100, "Atualização baixada e conferida. Reinicie pelo botão para aplicar com "
                                + "segurança.");
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (AppUpdateException e) {
            downloaded = null;
            throw e;
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            downloaded = null;
            throw new AppUpdateException("Não foi possível baixar a atualização. Nenhuma versão foi aplicada.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            downloaded = null;
            throw new AppUpdateException("O download foi interrompido. Nenhuma versão foi aplicada.", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void applyAndRestart() {
        lock.lock();
        try {
            if (downloaded == null || pendingArtifact == null) {
                throw new AppUpdateException("Baixe e confira a atualização antes de reiniciar.", null);
            }
            // confere de novo imediatamente antes de aplicar (o arquivo pode ter sido trocado em disco)
            var bytesOk = SignedManifestVerifier.matchesSha256(sha256(downloaded), pendingArtifact.sha256());
            if (!bytesOk) {
                throw new AppUpdateException("O pacote baixado foi alterado em disco e foi rejeitado.", null);
            }
            installer.verifyPlatformSignature(downloaded);
            installer.installAndRestart(downloaded);
        } catch (AppUpdateException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new AppUpdateException("Não foi possível iniciar a atualização. O aplicativo atual permanece "
                    + "disponível.", e);
        } finally {
            lock.unlock();
        }
    }

    private String feedError() {
        var feed = options.feedBaseUrl();
        if (feed == null || feed.toString().isBlank()) {
            return "O canal oficial ainda não foi publicado para esta instalação.";
        }
        var loopbackTest = "http".equalsIgnoreCase(feed.getScheme()) && "127.0.0.1".equals(feed.getHost())
                && Boolean.getBoolean("folhas.test.allowLoopbackProviders");
        if (!("https".equalsIgnoreCase(feed.getScheme()) || loopbackTest) || feed.getUserInfo() != null
                || feed.getQuery() != null || feed.getFragment() != null) {
            return "O endereço do canal de atualização não é HTTPS ou não está autorizado.";
        }
        if (verifier == null) {
            return "Esta instalação não tem a chave de publicação do escritório; atualizações ficam desativadas.";
        }
        return null;
    }

    private URI base() {
        var text = options.feedBaseUrl().toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private byte[] getBytes(URI uri, int maxBytes) throws IOException {
        try {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                var bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw new IOException("Resposta maior que o permitido.");
                }
                return bytes;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static byte[] sha256(Path file) throws IOException {
        try (var in = Files.newInputStream(file)) {
            var digest = MessageDigest.getInstance("SHA-256");
            var buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Snapshot snapshot(State state, String current, String latest, String message) {
        return new Snapshot(state, current, latest, activeChannel, 0, message);
    }
}
