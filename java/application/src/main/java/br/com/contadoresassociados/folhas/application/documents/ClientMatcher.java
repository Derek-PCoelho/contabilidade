package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionCandidate;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionMethod;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionRequest;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Motor de resolução de cliente, comum ao perfil local (SQLite) e ao servidor (PostgreSQL). A
 * ordem de precedência é a mesma do .NET: CNPJ exato → estabelecimento → CPF → raiz + nome →
 * código interno → razão social → apelido → sugestões aproximadas.
 *
 * <p>Correções: 1.1 — CNPJ alfanumérico (o .NET usava só dígitos e descartava letras); o passo de
 * raiz de 8 posições, que existia só no resolvedor local, agora vale também no servidor; 2.7/4.6 —
 * os índices são montados uma vez por catálogo e reaproveitados em {@link #resolveAll}.
 */
public final class ClientMatcher {

    static final BigDecimal EXACT = new BigDecimal("0.99");
    static final BigDecimal ROOT_AND_NAME = new BigDecimal("0.91");
    static final BigDecimal INTERNAL_CODE = new BigDecimal("0.95");
    static final BigDecimal LEGAL_NAME = new BigDecimal("0.90");
    static final BigDecimal ALIAS = new BigDecimal("0.88");
    static final BigDecimal AMBIGUOUS = new BigDecimal("0.50");
    static final double FUZZY_THRESHOLD = 0.70;
    static final double COHERENT_THRESHOLD = 0.78;

    private record Indexed(ClientDetails client, String taxId, String name, String preferred) {
    }

    private final List<Indexed> clients;
    private final Map<String, List<Indexed>> byTaxId = new HashMap<>();
    private final Map<String, List<Map.Entry<Indexed, EstablishmentModel>>> byEstablishment = new HashMap<>();

    public ClientMatcher(List<ClientDetails> catalog) {
        this.clients = catalog.stream().filter(Objects::nonNull).map(c -> new Indexed(c, taxKey(c.primaryTaxId()),
                normalizeName(c.legalNameOrFullName()), normalizeName(c.preferredName()))).toList();
        for (var item : clients) {
            byTaxId.computeIfAbsent(item.taxId(), k -> new ArrayList<>()).add(item);
            for (var e : item.client().establishments()) {
                byEstablishment.computeIfAbsent(taxKey(e.cnpj()), k -> new ArrayList<>()).add(Map.entry(item, e));
            }
        }
    }

    public List<ClientResolutionResult> resolveAll(List<ClientResolutionRequest> requests) {
        return requests.stream().map(this::resolve).toList();
    }

    public ClientResolutionResult resolve(ClientResolutionRequest request) {
        var fields = request == null ? List.<RecognizedField>of()
                : request.fields().stream().filter(f -> f != null && f.value() != null && !f.value().isBlank()).toList();
        var taxFields = fields.stream().filter(f -> f.role() == SemanticFieldRole.EMPLOYER_TAX_ID
                || f.role() == SemanticFieldRole.CLIENT_TAX_ID || f.role() == SemanticFieldRole.ESTABLISHMENT_TAX_ID)
                .toList();
        var cnpjFields = taxFields.stream().filter(f -> BrazilianRegistration.isValidCnpj(f.value())).toList();
        var rootFields = taxFields.stream().filter(f -> taxKey(f.value()).length() == BrazilianRegistration.CNPJ_ROOT_LENGTH)
                .toList();
        var cpfFields = taxFields.stream().filter(f -> f.role() == SemanticFieldRole.CLIENT_TAX_ID
                && BrazilianRegistration.isValidCpf(f.value())).toList();
        var nameFields = fields.stream().filter(f -> f.role() == SemanticFieldRole.EMPLOYER_NAME
                || f.role() == SemanticFieldRole.CLIENT_NAME).toList();
        var codeFields = fields.stream().filter(f -> f.role() == SemanticFieldRole.INTERNAL_CODE).toList();

        for (var field : cnpjFields) {
            var key = taxKey(field.value());
            var exact = byTaxId.getOrDefault(key, List.of());
            if (exact.size() == 1) {
                return result(exact.getFirst().client(), null, ClientResolutionMethod.EXACT_CLIENT_TAX_ID, EXACT, field);
            }
            var establishments = byEstablishment.getOrDefault(key, List.of());
            if (establishments.size() == 1) {
                var match = establishments.getFirst();
                return result(match.getKey().client(), match.getValue(), ClientResolutionMethod.EXACT_ESTABLISHMENT_TAX_ID,
                        EXACT, field);
            }
        }
        for (var field : cpfFields) {
            var exact = byTaxId.getOrDefault(taxKey(field.value()), List.of()).stream()
                    .filter(i -> i.client().personType() == PersonTypeModel.INDIVIDUAL).toList();
            if (exact.size() == 1) {
                return result(exact.getFirst().client(), null, ClientResolutionMethod.EXACT_INDIVIDUAL_TAX_ID, EXACT,
                        field);
            }
        }
        for (var field : Stream.concat(cnpjFields.stream(), rootFields.stream()).toList()) {
            var root = taxKey(field.value()).substring(0, BrazilianRegistration.CNPJ_ROOT_LENGTH);
            var rootMatches = clients.stream().filter(i -> i.client().personType() == PersonTypeModel.LEGAL_ENTITY
                    && i.taxId().startsWith(root)).toList();
            var coherent = rootMatches.stream().filter(i -> nameFields.stream().anyMatch(n -> {
                var name = normalizeName(n.value());
                return coherent(i.name(), name) || (!i.preferred().isEmpty() && coherent(i.preferred(), name));
            })).toList();
            if (coherent.size() == 1) {
                return result(coherent.getFirst().client(), null, ClientResolutionMethod.UNIQUE_CNPJ_ROOT_AND_NAME,
                        ROOT_AND_NAME, field);
            }
            if (rootMatches.stream().map(i -> i.client().id()).distinct().count() > 1) {
                return new ClientResolutionResult(null, null, null, null, ClientResolutionMethod.NONE, BigDecimal.ZERO,
                        List.of(), rootMatches.stream().map(i -> candidate(i.client(), null,
                                ClientResolutionMethod.UNIQUE_CNPJ_ROOT_AND_NAME, AMBIGUOUS)).toList(),
                        List.of("client.cnpj_root_ambiguous"));
            }
        }
        for (var field : codeFields) {
            var code = field.value().strip();
            var exact = clients.stream().filter(i -> i.client().internalCode() != null
                    && i.client().internalCode().equalsIgnoreCase(code)).toList();
            if (exact.size() == 1) {
                return result(exact.getFirst().client(), null, ClientResolutionMethod.EXACT_INTERNAL_CODE, INTERNAL_CODE,
                        field);
            }
        }
        for (var field : nameFields) {
            var name = normalizeName(field.value());
            var exact = clients.stream().filter(i -> i.name().equals(name)).toList();
            if (exact.size() == 1) {
                return result(exact.getFirst().client(), null, ClientResolutionMethod.EXACT_LEGAL_NAME, LEGAL_NAME, field);
            }
            var aliases = clients.stream().filter(i -> i.client().identifiers().stream().anyMatch(id -> id.isActive()
                    && id.type() == ClientIdentifierTypeModel.LEGAL_NAME_ALIAS && normalizeName(id.value()).equals(name)))
                    .toList();
            if (aliases.size() == 1) {
                return result(aliases.getFirst().client(), null, ClientResolutionMethod.EXACT_ALIAS, ALIAS, field);
            }
        }
        record Scored(Indexed item, double score) {
        }
        var fuzzy = nameFields.stream().map(f -> normalizeName(f.value())).filter(n -> !n.isEmpty())
                .flatMap(n -> clients.stream().map(i -> new Scored(i, similarity(n, i.name()))))
                .filter(s -> s.score() >= FUZZY_THRESHOLD)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .map(s -> candidate(s.item().client(), null, ClientResolutionMethod.FUZZY_SUGGESTION,
                        BigDecimal.valueOf(s.score()).setScale(4, java.math.RoundingMode.HALF_UP)))
                .distinct().limit(5).toList();
        return new ClientResolutionResult(null, null, null, null, ClientResolutionMethod.NONE, BigDecimal.ZERO, List.of(),
                fuzzy, List.of("client.not_resolved"));
    }

    private static ClientResolutionResult result(ClientDetails client, EstablishmentModel establishment,
            ClientResolutionMethod method, BigDecimal confidence, RecognizedField field) {
        var evidence = field.evidence() == null ? List.<br.com.contadoresassociados.folhas.contracts.documents.EvidenceBox>of()
                : List.of(field.evidence());
        if (!client.isActive() || (establishment != null && !establishment.isActive())) {
            return new ClientResolutionResult(null, null, null, null, ClientResolutionMethod.NONE, BigDecimal.ZERO,
                    evidence, List.of(candidate(client, establishment, method, confidence)), List.of("client.inactive"));
        }
        return new ClientResolutionResult(client.id(), establishment == null ? null : establishment.id(),
                displayName(client), mask(client.primaryTaxId()), method, confidence, evidence, List.of(), List.of());
    }

    private static ClientResolutionCandidate candidate(ClientDetails client, EstablishmentModel establishment,
            ClientResolutionMethod method, BigDecimal confidence) {
        return new ClientResolutionCandidate(client.id(), establishment == null ? null : establishment.id(),
                displayName(client), mask(client.primaryTaxId()), method, confidence);
    }

    static String displayName(ClientDetails client) {
        return client.preferredName() != null && !client.preferredName().isBlank() ? client.preferredName()
                : client.legalNameOrFullName();
    }

    /** Máscara LGPD igual à do .NET: CNPJ mostra os 2 primeiros e os 2 últimos; CPF o bloco do meio. */
    static String mask(String value) {
        if (value == null) {
            return "***";
        }
        return switch (value.length()) {
            case 14 -> value.substring(0, 2) + ".***.***/****-" + value.substring(12);
            case 11 -> "***." + value.substring(3, 6) + ".***-" + value.substring(9);
            default -> "***";
        };
    }

    /** Chave fiscal: remove pontuação e mantém letras (CNPJ alfanumérico) em maiúsculas. */
    static String taxKey(String value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder(value.length());
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }

    static boolean coherent(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        return left.equals(right) || left.contains(right) || right.contains(left)
                || similarity(left, right) >= COHERENT_THRESHOLD;
    }

    /** Maiúsculas, sem acentos, só letras/dígitos/espaço e espaços colapsados (igual ao .NET). */
    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        var decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        var sb = new StringBuilder(decomposed.length());
        for (var i = 0; i < decomposed.length(); i++) {
            var c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            }
        }
        return String.join(" ", sb.toString().toUpperCase(Locale.ROOT).trim().split("\\s+"));
    }

    /** 1 − distância de Levenshtein normalizada. */
    static double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        var previous = new int[right.length() + 1];
        for (var j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (var i = 1; i <= left.length(); i++) {
            var current = new int[right.length() + 1];
            current[0] = i;
            for (var j = 1; j <= right.length(); j++) {
                var cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length());
    }
}
