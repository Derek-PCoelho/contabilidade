package br.com.contadoresassociados.folhas.contracts.dispatch;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Catálogo dos campos ({{chave}}) aceitos em assunto e corpo das mensagens. */
public final class MessageTemplatePlaceholderCatalog {

    public record Definition(String key, String label, String description) {
        public String token() {
            return toToken(key);
        }
    }

    public record ValidationResult(List<String> referencedKeys, List<String> unknownKeys, List<String> malformed) {
        public boolean isValid() {
            return unknownKeys.isEmpty() && malformed.isEmpty();
        }
    }

    public static final String CLIENT_LEGAL_NAME = "cliente.razao_social";
    public static final String CLIENT_PREFERRED_NAME = "cliente.nome_preferencia";
    public static final String CLIENT_PREFERRED_OR_LEGAL_NAME = "cliente.nome_preferencia_ou_razao_social";
    public static final String CONTACT_NAME = "contato.nome";
    public static final String PERIOD_LABEL = "periodo.rotulo";
    public static final String DOCUMENT_LIST = "documentos.lista";
    public static final String DOCUMENT_COUNT = "documentos.quantidade";
    public static final String DUE_DATE_LIST = "vencimentos.lista";
    public static final String OPERATOR_NAME = "operador.nome";
    public static final String OFFICE_NAME = "escritorio.nome";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(CLIENT_PREFERRED_OR_LEGAL_NAME, "Nome do cliente",
                    "Usa o nome de preferência e, quando ele não existe, usa a razão social."),
            new Definition(CLIENT_LEGAL_NAME, "Razão social", "Insere a razão social ou o nome completo cadastrado."),
            new Definition(CLIENT_PREFERRED_NAME, "Nome de preferência",
                    "Insere o nome de preferência; se estiver vazio, usa a razão social."),
            new Definition(CONTACT_NAME, "Nome do contato",
                    "Insere o nome do contato principal que receberá a mensagem."),
            new Definition(PERIOD_LABEL, "Competência", "Insere o mês e o ano do grupo de documentos."),
            new Definition(DOCUMENT_LIST, "Lista de documentos", "Insere uma linha para cada documento anexado."),
            new Definition(DOCUMENT_COUNT, "Quantidade de documentos", "Insere o total de documentos anexados."),
            new Definition(DUE_DATE_LIST, "Vencimentos",
                    "Insere os vencimentos identificados ou informa que não foram encontrados."),
            new Definition(OFFICE_NAME, "Nome do escritório", "Insere o nome configurado para o escritório."),
            new Definition(OPERATOR_NAME, "Nome do operador",
                    "Insere a identificação do operador responsável pela preparação."));

    private static final Set<String> KEYS = DEFINITIONS.stream().map(Definition::key).collect(Collectors.toUnmodifiableSet());
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}\\r\\n]+?)\\s*\\}\\}");

    private MessageTemplatePlaceholderCatalog() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static Set<String> supportedKeys() {
        return KEYS;
    }

    public static boolean isSupported(String key) {
        return key != null && KEYS.contains(key.strip());
    }

    public static String toToken(String key) {
        if (!isSupported(key)) {
            throw new IllegalArgumentException("A chave informada não pertence ao catálogo de campos permitidos.");
        }
        return "{{" + key.strip() + "}}";
    }

    public static ValidationResult validate(String template) {
        if (template == null || template.isEmpty()) {
            return new ValidationResult(List.of(), List.of(), List.of());
        }
        var referenced = new TreeSet<String>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) {
            referenced.add(m.group(1).strip());
        }
        var unknown = referenced.stream().filter(k -> !KEYS.contains(k)).toList();
        var stripped = PLACEHOLDER.matcher(template).replaceAll("");
        var malformed = new java.util.ArrayList<String>();
        if (stripped.contains("{{") || stripped.contains("}}")) {
            malformed.add("{{ ou }} sem par");
        }
        return new ValidationResult(List.copyOf(referenced), unknown, List.copyOf(malformed));
    }

    public static String replaceTokens(String template, Function<String, String> resolver) {
        return PLACEHOLDER.matcher(template).replaceAll(match -> {
            var value = resolver.apply(match.group(1).strip());
            return Matcher.quoteReplacement(value == null ? match.group() : value);
        });
    }
}
