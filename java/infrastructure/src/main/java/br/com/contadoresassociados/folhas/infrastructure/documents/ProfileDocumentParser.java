package br.com.contadoresassociados.folhas.infrastructure.documents;

import br.com.contadoresassociados.folhas.application.documents.recognition.PdfModel;
import br.com.contadoresassociados.folhas.application.documents.recognition.RecognitionPorts;
import br.com.contadoresassociados.folhas.contracts.documents.EvidenceBox;
import br.com.contadoresassociados.folhas.contracts.documents.RecognitionFinding;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Extração de campos por rótulo "RÓTULO: valor" (mesmos perfis da versão .NET).
 * Correção 1.1: identificadores fiscais aceitam CNPJ alfanumérico (antes só dígitos eram mantidos,
 * o que destruía CNPJs com letras). Datas aceitam dd/MM/yyyy e yyyy-MM-dd; valores aceitam "R$ 1.234,56".
 */
public final class ProfileDocumentParser implements RecognitionPorts.DocumentParser {

    private static final BigDecimal CONFIDENCE = new BigDecimal("0.96");

    enum Kind { TEXT, TAX_ID, AMOUNT, DATE }

    record Spec(String name, String label, SemanticFieldRole role, Kind kind) {
    }

    private static Spec s(String name, String label, SemanticFieldRole role, Kind kind) {
        return new Spec(name, label, role, kind);
    }

    @Override
    public PdfModel.ParsedDocument parse(PdfModel.PdfTextExtraction ex, PdfModel.DocumentClassification cls) {
        if (cls.documentType() == RecognizedDocumentType.UNCLASSIFIED) {
            return new PdfModel.ParsedDocument(List.of(), List.of(new RecognitionFinding("document.profile_not_recognized",
                    "Nenhum dos sete perfis documentais foi reconhecido com segurança.", true)));
        }
        var fields = switch (cls.documentType()) {
            case VACATION -> read(ex, s("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                    s("Empregador", "EMPREGADOR", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                    s("EmpregadoCpf", "EMPREGADO CPF", SemanticFieldRole.EMPLOYEE_CPF, Kind.TAX_ID),
                    s("PeriodoDeGozo", "PERIODO DE GOZO", SemanticFieldRole.VACATION_PERIOD, Kind.TEXT),
                    s("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            case FGTS_DIGITAL -> read(ex, s("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                    s("RazaoSocial", "RAZAO SOCIAL", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                    s("Competencia", "COMPETENCIA", SemanticFieldRole.COMPETENCE, Kind.TEXT),
                    s("Vencimento", "VENCIMENTO", SemanticFieldRole.DUE_DATE, Kind.DATE),
                    s("ValorTotal", "VALOR TOTAL", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            case PAYROLL -> read(ex, s("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                    s("Empregador", "EMPREGADOR", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                    s("Competencia", "COMPETENCIA", SemanticFieldRole.COMPETENCE, Kind.TEXT),
                    s("TotalDaFolha", "TOTAL DA FOLHA", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            case FEDERAL_REVENUE_COLLECTION -> read(ex,
                    s("ContribuinteCnpj", "CONTRIBUINTE CNPJ", SemanticFieldRole.CLIENT_TAX_ID, Kind.TAX_ID),
                    s("RazaoSocial", "RAZAO SOCIAL", SemanticFieldRole.CLIENT_NAME, Kind.TEXT),
                    s("PeriodoApuracao", "PERIODO DE APURACAO", SemanticFieldRole.ASSESSMENT_PERIOD, Kind.DATE),
                    s("Vencimento", "VENCIMENTO", SemanticFieldRole.DUE_DATE, Kind.DATE),
                    s("ValorTotal", "VALOR TOTAL", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            case THIRTEENTH_SALARY -> {
                var f = read(ex, s("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                        s("Empregador", "EMPREGADOR", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                        s("EmpregadoCpf", "EMPREGADO CPF", SemanticFieldRole.EMPLOYEE_CPF, Kind.TAX_ID),
                        s("Competencia", "COMPETENCIA", SemanticFieldRole.COMPETENCE, Kind.TEXT),
                        s("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
                appendRepeatedTaxIds(ex, "EMPREGADOR CNPJ", "EmpregadorCnpj", SemanticFieldRole.EMPLOYER_TAX_ID, f);
                // Pendência 2.9: todos os CPFs de empregados entram na chave de duplicidade do 13º.
                appendRepeatedTaxIds(ex, "EMPREGADO CPF", "EmpregadoCpf", SemanticFieldRole.EMPLOYEE_CPF, f);
                yield f;
            }
            case PRO_LABORE -> read(ex, s("EmpresaCnpj", "EMPRESA CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                    s("Empresa", "EMPRESA", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                    s("SocioCpf", "SOCIO CPF", SemanticFieldRole.PARTNER_CPF, Kind.TAX_ID),
                    s("Competencia", "COMPETENCIA", SemanticFieldRole.COMPETENCE, Kind.TEXT),
                    s("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            case TERMINATION -> read(ex, s("SindicatoCnpj", "SINDICATO CNPJ", SemanticFieldRole.UNION_TAX_ID, Kind.TAX_ID),
                    s("EmpregadorCnpj", "EMPREGADOR CNPJ", SemanticFieldRole.EMPLOYER_TAX_ID, Kind.TAX_ID),
                    s("Empregador", "EMPREGADOR", SemanticFieldRole.EMPLOYER_NAME, Kind.TEXT),
                    s("TrabalhadorCpf", "TRABALHADOR CPF", SemanticFieldRole.EMPLOYEE_CPF, Kind.TAX_ID),
                    s("DataDesligamento", "DATA DE DESLIGAMENTO", SemanticFieldRole.EVENT_DATE, Kind.DATE),
                    s("ValorLiquido", "VALOR LIQUIDO", SemanticFieldRole.TOTAL_AMOUNT, Kind.AMOUNT));
            default -> new ArrayList<RecognizedField>();
        };
        var findings = new ArrayList<RecognitionFinding>();
        if (fields.stream().noneMatch(f -> f.role() == SemanticFieldRole.EMPLOYER_TAX_ID
                || f.role() == SemanticFieldRole.CLIENT_TAX_ID)) {
            findings.add(new RecognitionFinding("document.client_identifier_missing",
                    "O perfil foi reconhecido, mas não foi localizado identificador elegível do cliente.", true));
        }
        return new PdfModel.ParsedDocument(fields, findings);
    }

    private static List<RecognizedField> read(PdfModel.PdfTextExtraction ex, Spec... specs) {
        var result = new ArrayList<RecognizedField>();
        for (var spec : specs) {
            var found = find(ex, spec.label());
            if (found.isEmpty()) {
                continue;
            }
            var hit = found.getFirst();
            var value = normalize(hit.value(), spec.kind());
            if (value.isEmpty()) {
                continue;
            }
            result.add(new RecognizedField(spec.name(), value, display(value, spec.kind()), spec.role(), CONFIDENCE,
                    hit.evidence()));
        }
        return result;
    }

    record Hit(String value, EvidenceBox evidence) {
    }

    private static List<Hit> find(PdfModel.PdfTextExtraction ex, String label) {
        var normalizedLabel = DeterministicDocumentClassifier.normalize(label);
        var hits = new ArrayList<Hit>();
        for (var page : ex.pages()) {
            for (var raw : page.text().replace("\r\n", "\n").split("\n")) {
                var line = raw.strip();
                var sep = line.indexOf(':');
                if (sep < 0 || !DeterministicDocumentClassifier.normalize(line.substring(0, sep)).equals(normalizedLabel)) {
                    continue;
                }
                var value = line.substring(sep + 1).strip();
                var token = value.isEmpty() ? value : value.split("\\s+")[0];
                var word = token.isEmpty() ? null : page.words().stream()
                        .filter(w -> w.text().toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT)))
                        .findFirst().orElse(null);
                var evidence = word == null
                        ? new EvidenceBox(page.pageNumber(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                                BigDecimal.ZERO, line)
                        : new EvidenceBox(word.pageNumber(), word.x(), word.y(), word.width(), word.height(), line);
                hits.add(new Hit(value, evidence));
            }
        }
        return hits;
    }

    private static void appendRepeatedTaxIds(PdfModel.PdfTextExtraction ex, String label, String name,
            SemanticFieldRole role, List<RecognizedField> fields) {
        var existing = new HashSet<String>();
        fields.stream().filter(f -> f.role() == role).forEach(f -> existing.add(f.value()));
        for (var hit : find(ex, label)) {
            var value = normalize(hit.value(), Kind.TAX_ID);
            if ((value.length() != 14 && value.length() != 11) || !existing.add(value)) {
                continue;
            }
            fields.add(new RecognizedField(name + existing.size(), value, display(value, Kind.TAX_ID), role, CONFIDENCE,
                    hit.evidence()));
        }
    }

    static String normalize(String value, Kind kind) {
        return switch (kind) {
            case TAX_ID -> normalizeTaxId(value);
            case AMOUNT -> normalizeAmount(value);
            case DATE -> normalizeDate(value);
            case TEXT -> String.join(" ", value.strip().split("\\s+"));
        };
    }

    /** Mantém dígitos e letras de CNPJ; se não formar CNPJ válido, cai para somente dígitos (CPF etc.). */
    static String normalizeTaxId(String value) {
        var token = value.strip().split("\\s+")[0];
        var cnpj = BrazilianRegistration.tryNormalizeCnpj(token);
        if (cnpj.isValid()) {
            return cnpj.normalized();
        }
        var sb = new StringBuilder();
        value.chars().filter(c -> c >= '0' && c <= '9').forEach(c -> sb.append((char) c));
        return sb.toString();
    }

    static String normalizeAmount(String value) {
        var clean = value.replaceAll("(?i)R\\$", "").strip();
        if (!clean.matches("-?[0-9.]*,?[0-9]*") || clean.isEmpty()) {
            return clean;
        }
        try {
            return new BigDecimal(clean.replace(".", "").replace(",", ".")).setScale(2, java.math.RoundingMode.HALF_EVEN)
                    .toPlainString();
        } catch (NumberFormatException e) {
            return clean;
        }
    }

    static String normalizeDate(String value) {
        var v = value.strip();
        for (var pattern : List.of("dd/MM/uuuu", "d/M/uuuu", "uuuu-MM-dd", "dd-MM-uuuu")) {
            try {
                return LocalDate.parse(v, DateTimeFormatter.ofPattern(pattern, Locale.ROOT)).toString();
            } catch (DateTimeParseException ignored) {
                // tenta o próximo
            }
        }
        return v;
    }

    static String display(String value, Kind kind) {
        if (kind != Kind.TAX_ID) {
            return value;
        }
        return switch (value.length()) {
            case 14 -> value.substring(0, 2) + ".***.***/****-" + value.substring(12);
            case 11 -> "***." + value.substring(3, 6) + ".***-" + value.substring(9);
            default -> "***";
        };
    }
}
