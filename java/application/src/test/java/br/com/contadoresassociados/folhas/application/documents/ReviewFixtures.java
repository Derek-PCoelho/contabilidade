package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Utilitários de teste: store em memória com versão, resolvedor programável e PDFs falsos. */
public final class ReviewFixtures {

    public static final Instant NOW = Instant.parse("2026-09-30T15:00:00Z");

    public static final class MemoryStore implements DocumentReviewStore {
        public DocumentReviewWorkspace saved;
        public int saveCount;

        @Override
        public DocumentReviewWorkspace load(String scopeKey) {
            return saved == null ? DocumentReviewWorkspace.empty(scopeKey) : saved;
        }

        @Override
        public DocumentReviewWorkspace save(DocumentReviewWorkspace ws, long expectedVersion) {
            var current = saved == null ? 0 : saved.version();
            if (current != expectedVersion) {
                throw new WorkspaceConflictException(ws.scopeKey(), expectedVersion, current);
            }
            saved = ws.toBuilder().version(current + 1).build();
            saveCount++;
            return saved;
        }
    }

    /** Resolvedor que devolve sempre o resultado configurado e conta chamadas em lote. */
    public static final class ProgrammableResolver implements ClientResolver {
        public final AtomicReference<ClientResolutionResult> next = new AtomicReference<>();
        final AtomicInteger batchCalls = new AtomicInteger();
        final List<ClientResolutionRequest> received = new ArrayList<>();

        @Override
        public ClientResolutionResult resolve(ClientResolutionRequest request) {
            received.add(request);
            return next.get();
        }

        @Override
        public List<ClientResolutionResult> resolveBatch(List<ClientResolutionRequest> requests) {
            batchCalls.incrementAndGet();
            return requests.stream().map(this::resolve).toList();
        }
    }

    public static ClientResolutionResult resolved(UUID clientId, String name) {
        return new ClientResolutionResult(clientId, null, name, "**.***.***/****-81",
                ClientResolutionMethod.EXACT_CLIENT_TAX_ID, new BigDecimal("0.98"), List.of(), List.of(), List.of());
    }

    public static RecognizedField field(String name, String value, SemanticFieldRole role) {
        return new RecognizedField(name, value, value, role, BigDecimal.ONE,
                new EvidenceBox(1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
                        "Trecho bruto com CPF 529.982.247-25 de empregado"));
    }

    public static DocumentRecognitionResult payroll(Path file, UUID clientId, String competence) throws IOException {
        var sha = FileFingerprintCache.computeSha256(file);
        return new DocumentRecognitionResult(file.getFileName().toString(), sha, "application/pdf", Files.size(file), 1,
                RecognizedDocumentType.PAYROLL, "p1", RecognitionConfidence.HIGH, BigDecimal.ONE, false, false,
                List.of(field("CNPJ", "11222333000181", SemanticFieldRole.EMPLOYER_TAX_ID),
                        field("Competência", competence, SemanticFieldRole.COMPETENCE),
                        field("Total", "1500.00", SemanticFieldRole.TOTAL_AMOUNT),
                        field("Empregado", "529.982.247-25", SemanticFieldRole.EMPLOYEE_CPF)),
                resolved(clientId, "Empresa A"), List.of());
    }

    public static Path pdf(Path dir, String name, String content) throws IOException {
        var p = dir.resolve(name);
        Files.writeString(p, "%PDF-1.7\n" + content);
        return p;
    }

    public static DocumentReviewService service(MemoryStore store, ClientResolver resolver) {
        var options = DocumentReviewOptions.defaults();
        return new DocumentReviewService(store, () -> new DocumentReviewContext("org:1|local", "ator-1", "Ana"),
                new DocumentPeriodParser(), ValidationRules.defaults(options), options, Clock.fixed(NOW), resolver);
    }

    private ReviewFixtures() {
    }
}
