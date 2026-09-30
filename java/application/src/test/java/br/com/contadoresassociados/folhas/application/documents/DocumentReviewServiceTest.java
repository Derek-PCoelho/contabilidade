package br.com.contadoresassociados.folhas.application.documents;

import static br.com.contadoresassociados.folhas.application.documents.ReviewFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.application.common.CancellationToken;
import br.com.contadoresassociados.folhas.contracts.documents.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentReviewServiceTest {

    static final CancellationToken CT = CancellationToken.NONE;

    @TempDir
    Path dir;

    @Test
    void importGroupsAndApproves() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var client = UUID.randomUUID();
        resolver.next.set(resolved(client, "Empresa A"));
        var svc = service(store, resolver);
        var ws = svc.importDocument(pdf(dir, "a.pdf", "a").toString(), payroll(pdf(dir, "a.pdf", "a"), client, "03/2026"), CT);
        assertThat(ws.groups()).singleElement().satisfies(g -> {
            assertThat(g.state()).isEqualTo(ReviewGroupState.READY_FOR_REVIEW);
            assertThat(g.periodLabel()).isEqualTo("03/2026");
        });
        ws = svc.approveGroup(ws.groups().getFirst().id(), CT);
        assertThat(ws.groups().getFirst().isApproved()).isTrue();
        assertThat(ws.documents().getFirst().state()).isEqualTo(ReviewDocumentState.APPROVED);
    }

    /** Pendência 2.1: a trava de troca de cliente persiste nas revalidações seguintes. */
    @Test
    void clientChangeLockPersistsUntilHumanConfirmation() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        resolver.next.set(resolved(a, "Empresa A"));
        var svc = service(store, resolver);
        var file = pdf(dir, "x.pdf", "x");
        svc.importDocument(file.toString(), payroll(file, a, "03/2026"), CT);

        resolver.next.set(resolved(b, "Empresa B"));
        var ws = svc.revalidate(CT);
        var doc = ws.documents().getFirst();
        assertThat(doc.pendingClientChangeFrom()).isEqualTo(a);
        assertThat(doc.state()).isEqualTo(ReviewDocumentState.BLOCKED);
        assertThat(ws.groups()).isEmpty();

        // Na versão .NET a trava sumia aqui: nova revalidação com o mesmo resultado.
        ws = svc.revalidate(CT);
        ws = svc.revalidate(CT);
        doc = ws.documents().getFirst();
        assertThat(doc.pendingClientChangeFrom()).isEqualTo(a);
        assertThat(doc.state()).isEqualTo(ReviewDocumentState.BLOCKED);
        assertThat(doc.findings()).extracting(ValidationFinding::ruleCode).contains("client.assignment_changed");
        assertThat(doc.clientAlternatives()).extracting(ClientResolutionCandidate::clientId).contains(a, b);

        var candidate = doc.clientAlternatives().stream().filter(c -> c.clientId().equals(b)).findFirst().orElseThrow();
        ws = svc.overrideClient(doc.id(), candidate, "Confirmado com o cliente por telefone", CT);
        doc = ws.documents().getFirst();
        assertThat(doc.pendingClientChangeFrom()).isNull();
        assertThat(doc.clientId()).isEqualTo(b);
        assertThat(doc.state()).isEqualTo(ReviewDocumentState.GROUPED);
        assertThat(ws.auditEvents()).extracting(ReviewAuditEvent::action).contains("document.client_change_confirmed");
    }

    /** Pendência 2.1 (segunda metade): override manual para outro cliente se sustenta. */
    @Test
    void manualOverrideToDifferentClientIsKept() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var withAlternative = new ClientResolutionResult(a, null, "Empresa A", "***", ClientResolutionMethod.EXACT_CLIENT_TAX_ID,
                java.math.BigDecimal.ONE, List.of(), List.of(new ClientResolutionCandidate(b, null, "Empresa B", "***",
                        ClientResolutionMethod.FUZZY_SUGGESTION, new java.math.BigDecimal("0.5"))), List.of());
        resolver.next.set(withAlternative);
        var svc = service(store, resolver);
        var file = pdf(dir, "y.pdf", "y");
        var ws = svc.importDocument(file.toString(), payroll(file, a, "04/2026"), CT);
        var alt = ws.documents().getFirst().clientAlternatives().stream().filter(c -> c.clientId().equals(b))
                .findFirst().orElseThrow();
        ws = svc.overrideClient(ws.documents().getFirst().id(), alt, "Documento é da filial B, conferido", CT);
        ws = svc.revalidate(CT);
        var doc = ws.documents().getFirst();
        assertThat(doc.clientId()).isEqualTo(b);
        assertThat(doc.resolutionMethod()).isEqualTo(ClientResolutionMethod.MANUAL_OVERRIDE);
        assertThat(doc.pendingClientChangeFrom()).isNull();
    }

    /** Pendências 2.6 e 2.7: um lote por revalidação, sem CPF de empregado nem snippet bruto. */
    @Test
    void resolutionIsBatchedAndMinimized() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var client = UUID.randomUUID();
        resolver.next.set(resolved(client, "Empresa A"));
        var svc = service(store, resolver);
        var imports = new java.util.ArrayList<DocumentReviewService.Import>();
        for (var i = 0; i < 5; i++) {
            var f = pdf(dir, "d" + i + ".pdf", "conteudo " + i);
            imports.add(new DocumentReviewService.Import(f.toString(), payroll(f, client, "05/2026")));
        }
        svc.importDocuments(imports, CT);
        assertThat(resolver.batchCalls.get()).isEqualTo(1);
        assertThat(store.saveCount).isEqualTo(1);
        assertThat(resolver.received).allSatisfy(req -> {
            assertThat(req.fields()).noneMatch(f -> f.role() == SemanticFieldRole.EMPLOYEE_CPF);
            assertThat(req.fields()).allSatisfy(f -> assertThat(f.evidence().snippet()).doesNotContain("529.982"));
        });
    }

    @Test
    void hashChangeBlocksAndInvalidatesApproval() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var client = UUID.randomUUID();
        resolver.next.set(resolved(client, "Empresa A"));
        var svc = service(store, resolver);
        var file = pdf(dir, "h.pdf", "original");
        var ws = svc.importDocument(file.toString(), payroll(file, client, "06/2026"), CT);
        ws = svc.approveGroup(ws.groups().getFirst().id(), CT);
        Files.writeString(file, "%PDF-1.7\nadulterado com outro tamanho");
        ws = svc.revalidate(CT);
        assertThat(ws.documents().getFirst().findings()).extracting(ValidationFinding::ruleCode)
                .contains("document.hash_changed");
        assertThat(ws.groups()).noneMatch(DocumentDispatchGroup::isApproved);
    }

    @Test
    void exactDuplicateIsBlocked() throws Exception {
        var store = new MemoryStore();
        var resolver = new ProgrammableResolver();
        var client = UUID.randomUUID();
        resolver.next.set(resolved(client, "Empresa A"));
        var svc = service(store, resolver);
        var f1 = pdf(dir, "1.pdf", "igual");
        var f2 = pdf(dir, "2.pdf", "igual");
        svc.importDocument(f1.toString(), payroll(f1, client, "07/2026"), CT);
        var ws = svc.importDocument(f2.toString(), payroll(f2, client, "07/2026"), CT);
        assertThat(ws.documents()).extracting(ReviewDocument::state)
                .containsExactly(ReviewDocumentState.GROUPED, ReviewDocumentState.DUPLICATE);
    }

    @Test
    void reasonRequiredAndMergeRulesEnforced() throws Exception {
        var svc = service(new MemoryStore(), new ProgrammableResolver());
        assertThatThrownBy(() -> svc.removeDocument(UUID.randomUUID(), "curto", CT))
                .isInstanceOfSatisfying(DocumentReviewException.class,
                        e -> assertThat(e.code()).isEqualTo("document.removal_reason_required"));
        var id = UUID.randomUUID();
        assertThatThrownBy(() -> svc.mergeGroups(id, id, "justificativa longa", CT))
                .isInstanceOfSatisfying(DocumentReviewException.class,
                        e -> assertThat(e.code()).isEqualTo("group.merge_same_group"));
    }

    /** Pendência 3.13: salvar sobre versão desatualizada falha em vez de sobrescrever. */
    @Test
    void storeRejectsStaleVersion() {
        var store = new MemoryStore();
        store.save(DocumentReviewWorkspace.empty("s"), 0);
        assertThatThrownBy(() -> store.save(DocumentReviewWorkspace.empty("s"), 0))
                .isInstanceOf(WorkspaceConflictException.class);
    }

    @Test
    void periodParserUnderstandsMonthNames() {
        var parser = new DocumentPeriodParser();
        for (var text : List.of("MARÇO/2026", "mar/2026", "Março de 2026", "03/2026", "3-2026")) {
            var p = parser.parse(List.of(field("C", text, SemanticFieldRole.COMPETENCE)));
            assertThat(p.displayLabel()).as(text).isEqualTo("03/2026");
        }
        assertThat(parser.parse(List.of(field("C", "13/2026", SemanticFieldRole.COMPETENCE))).kind())
                .isEqualTo(DocumentPeriodKind.UNKNOWN);
    }

    @Test
    void cancellationStopsBeforeSaving() throws Exception {
        var store = new MemoryStore();
        var svc = service(store, new ProgrammableResolver());
        var ct = CancellationToken.create();
        ct.cancel();
        var file = pdf(dir, "c.pdf", "c");
        assertThatThrownBy(() -> svc.importDocument(file.toString(), payroll(file, UUID.randomUUID(), "01/2026"), ct))
                .isInstanceOf(br.com.contadoresassociados.folhas.application.common.OperationCancelledException.class);
        assertThat(store.saveCount).isZero();
    }
}
