package br.com.contadoresassociados.folhas.application.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryFilteringTest {

    static final ZoneId BRT = ZoneId.of("America/Sao_Paulo");

    @Test
    void dayWindowUsesLocalMidnight() {
        var w = HistoryFiltering.TimeWindow.forDay(LocalDate.of(2026, 9, 30), BRT);
        assertThat(w.startUtc()).isEqualTo(OffsetDateTime.parse("2026-09-30T03:00Z"));
        assertThat(w.contains(OffsetDateTime.parse("2026-10-01T02:59:59Z"))).isTrue();
        assertThat(w.contains(OffsetDateTime.parse("2026-10-01T03:00Z"))).isFalse();
    }

    @Test
    void nonexistentLocalTimeIsRejected() {
        // Horário de verão de 2018 em São Paulo: 04/11/2018 00:00 não existiu.
        assertThatThrownBy(() -> HistoryFiltering.TimeWindow.toUtc(LocalDateTime.of(2018, 11, 4, 0, 0), BRT))
                .hasMessageContaining("não existe");
    }

    @Test
    void competenceParsingAndAccentInsensitiveSearch() {
        assertThat(HistoryFiltering.Competence.parse("03/2026")).contains(new HistoryFiltering.Competence(2026, 3));
        assertThat(HistoryFiltering.Competence.parse("2026-11")).contains(new HistoryFiltering.Competence(2026, 11));
        assertThat(HistoryFiltering.Competence.parse("sem ano")).isEmpty();
        var client = UUID.randomUUID();
        var entry = new HistoryFiltering.Entry(UUID.randomUUID(), OffsetDateTime.parse("2026-09-30T12:00Z"), client,
                "Padaria São João", List.of(new HistoryFiltering.Competence(2026, 3)),
                List.of(new HistoryFiltering.DocumentReference(null, RecognizedDocumentType.PAYROLL, "folha.pdf")),
                "dispatch_approved");
        var c = new HistoryFiltering.Criteria(null, 2026, 3, client, null, RecognizedDocumentType.PAYROLL, "sao joao");
        assertThat(HistoryFiltering.matches(entry, c)).isTrue();
        assertThat(HistoryFiltering.matches(entry, new HistoryFiltering.Criteria(null, 2026, 4, null, null, null,
                null))).isFalse();
        assertThat(HistoryFiltering.matches(entry, new HistoryFiltering.Criteria(null, null, null, null, null, null,
                "padaria maria"))).isFalse();
    }
}
