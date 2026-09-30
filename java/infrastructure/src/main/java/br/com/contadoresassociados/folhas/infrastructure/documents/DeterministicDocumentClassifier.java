package br.com.contadoresassociados.folhas.infrastructure.documents;

import br.com.contadoresassociados.folhas.application.documents.recognition.PdfModel;
import br.com.contadoresassociados.folhas.application.documents.recognition.RecognitionPorts;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Classificador por âncoras (mesmos 7 perfis e ordem de desempate da versão .NET). */
public final class DeterministicDocumentClassifier implements RecognitionPorts.DocumentClassifier {

    static final String PROFILE_VERSION = "phase4-profiles-v1";

    record Profile(RecognizedDocumentType type, List<String> anchors) {
    }

    static final List<Profile> PROFILES = List.of(
            new Profile(RecognizedDocumentType.VACATION, List.of("RECIBO DE FERIAS", "PERIODO DE GOZO")),
            new Profile(RecognizedDocumentType.FGTS_DIGITAL, List.of("FGTS DIGITAL", "COMPETENCIA")),
            new Profile(RecognizedDocumentType.PAYROLL, List.of("FOLHA DE PAGAMENTO", "TOTAL DA FOLHA")),
            new Profile(RecognizedDocumentType.FEDERAL_REVENUE_COLLECTION, List.of("DARF", "PERIODO DE APURACAO")),
            new Profile(RecognizedDocumentType.THIRTEENTH_SALARY, List.of("DECIMO TERCEIRO SALARIO", "EMPREGADO CPF")),
            new Profile(RecognizedDocumentType.PRO_LABORE, List.of("PRO-LABORE", "SOCIO CPF")),
            new Profile(RecognizedDocumentType.TERMINATION, List.of("TERMO DE RESCISAO", "TRABALHADOR CPF")));

    @Override
    public PdfModel.DocumentClassification classify(PdfModel.PdfTextExtraction extraction) {
        var text = normalize(extraction.fullText());
        record Ranked(RecognizedDocumentType type, List<String> matches, BigDecimal score) {
        }
        var best = PROFILES.stream().map(p -> {
            var m = p.anchors().stream().filter(text::contains).toList();
            return new Ranked(p.type(), m, BigDecimal.valueOf(m.size()).divide(BigDecimal.valueOf(p.anchors().size()),
                    4, RoundingMode.HALF_EVEN));
        }).sorted(Comparator.comparing(Ranked::score).reversed().thenComparing(r -> r.type().ordinal())).findFirst()
                .orElseThrow();
        if (best.score().compareTo(BigDecimal.ONE) < 0) {
            return new PdfModel.DocumentClassification(RecognizedDocumentType.UNCLASSIFIED, PROFILE_VERSION,
                    best.score().multiply(new BigDecimal("0.5")).stripTrailingZeros(), best.matches());
        }
        return new PdfModel.DocumentClassification(best.type(), PROFILE_VERSION, BigDecimal.ONE, best.matches());
    }

    /** Remove acentos, maiúsculas invariantes e colapsa espaços. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        var decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        var sb = new StringBuilder(decomposed.length());
        decomposed.codePoints().filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK)
                .forEach(sb::appendCodePoint);
        return String.join(" ", Normalizer.normalize(sb.toString(), Normalizer.Form.NFC).toUpperCase(Locale.ROOT)
                .strip().split("\\s+"));
    }
}
