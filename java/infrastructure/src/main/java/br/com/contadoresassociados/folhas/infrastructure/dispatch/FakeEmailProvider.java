package br.com.contadoresassociados.folhas.infrastructure.dispatch;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.dispatch.EmailProvider;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttempt;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailEnvelope;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderAccount;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderCapabilities;
import br.com.contadoresassociados.folhas.contracts.dispatch.EmailProviderResult;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Provedor local seguro: grava um "recibo" por chave de idempotência e devolve sempre o mesmo
 * resultado para a mesma chave. O formato do recibo é o mesmo do .NET (reconciliação de recibos
 * antigos continua funcionando).
 *
 * <p>Pendência 5.7: destinatários, assunto e corpo não são mais gravados em claro — ficam
 * mascarados/resumidos por hash — e recibos mais antigos que a retenção são apagados.
 */
public final class FakeEmailProvider implements EmailProvider {

    public static final String ACCOUNT_ID = "fake://local";
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    public record Options(Path outputDirectory, Duration simulatedLatency, Duration retention) {

        public Options {
            outputDirectory = outputDirectory == null ? defaultDirectory() : outputDirectory;
            simulatedLatency = simulatedLatency == null ? Duration.ofMillis(75) : simulatedLatency;
            retention = retention == null ? Duration.ofDays(7) : retention;
        }

        public static Options defaults() {
            return new Options(null, null, null);
        }

        public static Path defaultDirectory() {
            return Path.of(System.getProperty("java.io.tmpdir"), "folhas-da-michelly-fake-outbox");
        }
    }

    record FakeAttachment(String fileName, String sha256, long fileSizeBytes) {
    }

    record FakeProviderReceipt(String idempotencyKey, UUID dispatchItemId, String senderAccountId, List<String> to,
            List<String> cc, String subject, String textBody, String htmlBody, List<FakeAttachment> attachments,
            EmailProviderResult result, OffsetDateTime createdAtUtc) {
    }

    private final Options options;
    private final Clock clock;

    public FakeEmailProvider(Options options, Clock clock) {
        this.options = options == null ? Options.defaults() : options;
        this.clock = clock == null ? Clock.system() : clock;
    }

    @Override
    public String providerKey() {
        return DispatchWorkflowOptions.FAKE_PROVIDER;
    }

    @Override
    public EmailProviderAccount account() {
        return new EmailProviderAccount(providerKey(), ACCOUNT_ID, "Caixa local simulada", true);
    }

    @Override
    public EmailProviderCapabilities capabilities() {
        return new EmailProviderCapabilities(true, true, true, MAX_ATTACHMENT_BYTES);
    }

    @Override
    public EmailProviderResult createDraft(EmailEnvelope envelope) {
        return execute(envelope, true);
    }

    @Override
    public EmailProviderResult send(EmailEnvelope envelope) {
        return execute(envelope, false);
    }

    @Override
    public EmailProviderResult reconcile(DeliveryAttempt attempt) {
        delay();
        try {
            var path = receiptPath(attempt.idempotencyKey());
            if (!Files.exists(path)) {
                return notFound();
            }
            return readReceipt(path).result();
        } catch (IOException | UncheckedIOException e) {
            return ProviderSupport.ambiguous("FAKE_RECEIPT_UNREADABLE",
                    "O recibo local não pôde ser lido; confira novamente.", null);
        }
    }

    /** Remove recibos mais antigos que a retenção configurada. Devolve quantos foram apagados. */
    public int purgeExpired() {
        var dir = options.outputDirectory();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        var limit = clock.now().minus(options.retention());
        var removed = 0;
        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                var name = file.getFileName().toString();
                if (!name.endsWith(".json") && !name.endsWith(".tmp")) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(file).toInstant().isBefore(limit)) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // arquivo em uso/removido concorrentemente: tenta na próxima limpeza
                }
            }
        } catch (IOException ignored) {
            return removed;
        }
        return removed;
    }

    private EmailProviderResult execute(EmailEnvelope envelope, boolean isDraft) {
        if (envelope == null || envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank()) {
            return ProviderSupport.permanent("FAKE_ENVELOPE_INVALID", "O envelope local está incompleto.", null);
        }
        try {
            ensureDirectory();
            purgeExpired();
            delay();
            var path = receiptPath(envelope.idempotencyKey());
            if (Files.exists(path)) {
                return readReceipt(path).result();
            }
            var suffix = stableSuffix(envelope.idempotencyKey());
            var successful = isDraft
                    ? new EmailProviderResult(DeliveryAttemptState.DRAFT_CREATED, null, "fake-draft-" + suffix, null, null)
                    : new EmailProviderResult(DeliveryAttemptState.ACCEPTED_BY_PROVIDER, "fake-message-" + suffix, null,
                            null, null);
            var scenario = envelope.scenario() == null
                    ? br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario.SUCCESS
                    : envelope.scenario();
            var returned = switch (scenario) {
                case SUCCESS -> successful;
                case TRANSIENT_FAILURE -> ProviderSupport.transientFailure("FAKE_TRANSIENT_FAILURE",
                        "Falha transitória simulada pelo provedor local.", null);
                case PERMANENT_FAILURE -> ProviderSupport.permanent("FAKE_PERMANENT_FAILURE",
                        "Falha permanente simulada pelo provedor local.", null);
                case TIMEOUT -> ProviderSupport.ambiguous("FAKE_TIMEOUT_AFTER_ACCEPTANCE",
                        "Timeout simulado após aceite local; reconciliação obrigatória.", null);
                case AMBIGUOUS -> ProviderSupport.ambiguous("FAKE_AMBIGUOUS_RESULT",
                        "Resultado ambíguo simulado; nova tentativa cega é proibida.", null);
            };
            var persisted = scenario
                    == br.com.contadoresassociados.folhas.contracts.dispatch.FakeDeliveryScenario.TIMEOUT
                    ? successful : returned;
            var receipt = new FakeProviderReceipt(envelope.idempotencyKey(), envelope.dispatchItemId(),
                    envelope.senderAccountId(), envelope.to().stream().map(FakeEmailProvider::maskAddress).toList(),
                    envelope.cc().stream().map(FakeEmailProvider::maskAddress).toList(), digest(envelope.subject()),
                    digest(envelope.textBody()), digest(envelope.htmlBody()),
                    envelope.attachments().stream()
                            .map(a -> new FakeAttachment(a.fileName(), a.sha256(), a.fileSizeBytes())).toList(),
                    persisted, clock.nowUtc());
            writeAtomically(path, receipt);
            return returned;
        } catch (IOException | UncheckedIOException e) {
            return ProviderSupport.ambiguous("FAKE_OUTBOX_UNAVAILABLE",
                    "A caixa local não pôde ser gravada; confira antes de repetir.", null);
        }
    }

    private void ensureDirectory() throws IOException {
        var dir = options.outputDirectory();
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows: o diretório herda a ACL do perfil do usuário
        }
    }

    private void delay() {
        var ms = options.simulatedLatency().toMillis();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path receiptPath(String idempotencyKey) {
        return options.outputDirectory().resolve(stableSuffix(idempotencyKey) + ".json");
    }

    static String stableSuffix(String idempotencyKey) {
        return ProviderSupport.sha256Hex(idempotencyKey).substring(0, 24).toLowerCase(Locale.ROOT);
    }

    private static FakeProviderReceipt readReceipt(Path path) throws IOException {
        var receipt = Json.read(Files.readAllBytes(path), FakeProviderReceipt.class);
        if (receipt == null || receipt.result() == null) {
            throw new IOException("Invalid fake provider receipt.");
        }
        return receipt;
    }

    private static void writeAtomically(Path path, FakeProviderReceipt receipt) throws IOException {
        var tmp = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID().toString().replace("-", "") + ".tmp");
        try {
            Files.write(tmp, Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path);
            }
        } catch (FileAlreadyExistsException ignored) {
            // outra execução gravou o mesmo recibo primeiro: o recibo existente prevalece
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static EmailProviderResult notFound() {
        return ProviderSupport.permanent("FAKE_RECEIPT_NOT_FOUND",
                "Nenhum recibo local foi localizado para a chave informada.", null);
    }

    /** {@code maria.silva@empresa.com.br → m***@empresa.com.br}. */
    static String maskAddress(String address) {
        if (address == null) {
            return null;
        }
        var at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }

    private static String digest(String text) {
        if (text == null) {
            return null;
        }
        return "sha256:" + ProviderSupport.sha256Hex(text).substring(0, 16) + " (" + text.length() + " caracteres)";
    }
}
