package br.com.contadoresassociados.folhas.infrastructure.reports;

import br.com.contadoresassociados.folhas.application.documents.DocumentPresentation;
import br.com.contadoresassociados.folhas.contracts.dispatch.DeliveryAttemptState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchOperationMode;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.ReviewDocumentState;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Textos amigáveis dos relatórios (mesmo vocabulário da versão .NET). */
final class ReportText {

    private ReportText() {
    }

    /**
     * Pendência 7.8: neutraliza injeção de fórmula em CSV/XLSX. Além de {@code = + - @} (já tratados no .NET),
     * cobre TAB, CR e LF iniciais e o sinal de igual de largura total.
     */
    static String sanitize(String value) {
        var text = value == null ? "" : value;
        if (text.isEmpty()) {
            return text;
        }
        var first = text.charAt(0);
        return switch (first) {
            case '=', '+', '-', '@', '\t', '\r', '\n', '\uFF1D', '\uFF0B', '\uFF0D', '\uFF20' -> "'" + text;
            default -> text;
        };
    }

    static String mode(DispatchOperationMode mode) {
        return switch (mode) {
            case TEST -> "Teste seguro (sem envio real)";
            case DRAFT -> "Salvar como rascunho";
            case SEND -> "Envio aos destinatários";
        };
    }

    static String state(DispatchItemState s) {
        return switch (s) {
            case BLOCKED -> "Precisa de correção";
            case READY_FOR_APPROVAL -> "Pronto para conferir";
            case APPROVED -> "Aprovado";
            case DRAFT_CREATING -> "Criando rascunho";
            case DRAFT_CREATED -> "Rascunho salvo";
            case SENDING -> "Enviando";
            case ACCEPTED_BY_PROVIDER -> "Aceito pelo serviço de e-mail";
            case FAILED -> "Não concluído";
            case AMBIGUOUS -> "Resultado incerto — conferir";
            case RECONCILED -> "Situação conferida";
            case COMPLETED -> "Concluído";
            case CANCELLED -> "Cancelado";
        };
    }

    static String attempt(DeliveryAttemptState s) {
        return switch (s) {
            case PENDING -> "Em processamento";
            case DRAFT_CREATED -> "Rascunho salvo";
            case ACCEPTED_BY_PROVIDER -> "Aceito pelo serviço de e-mail";
            case FAILED_TRANSIENT -> "Falha temporária";
            case FAILED_PERMANENT -> "Falha permanente";
            case AMBIGUOUS -> "Resultado incerto — conferir";
            case RECONCILED -> "Situação conferida";
        };
    }

    static String review(ReviewDocumentState s) {
        return switch (s) {
            case BLOCKED -> "Precisa de correção";
            case READY -> "Pronto para organizar";
            case GROUPED -> "Pronto para revisar";
            case APPROVED -> "Aprovado";
            case DUPLICATE -> "Documento repetido";
        };
    }

    static String severity(ValidationSeverity s) {
        return switch (s) {
            case INFO -> "Informação";
            case WARNING -> "Atenção";
            case ERROR -> "Corrigir antes de continuar";
            case BLOCKER -> "Ação obrigatória";
        };
    }

    static String documentType(RecognizedDocumentType t) {
        return DocumentPresentation.label(t);
    }

    static String actor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "Aplicativo";
        }
        var lower = actor.toLowerCase(Locale.ROOT);
        return lower.startsWith("unauthenticated-") || lower.startsWith("local-") ? "Operador local" : actor;
    }

    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("document.imported", "Documento importado"), Map.entry("document_imported", "Documento importado"),
            Map.entry("document.validated", "Documento conferido"),
            Map.entry("document.grouped", "Documento organizado em conjunto"),
            Map.entry("document.period_corrected", "Competência corrigida"),
            Map.entry("document.period_restored", "Competência original restaurada"),
            Map.entry("document.client_overridden", "Cliente corrigido manualmente"),
            Map.entry("document.client_change_confirmed", "Troca de cliente confirmada"),
            Map.entry("document.removed_from_review", "Documento retirado da revisão"),
            Map.entry("workspace.revalidated", "Documentos conferidos novamente"),
            Map.entry("workspace_revalidated", "Documentos conferidos novamente"),
            Map.entry("group.approved", "Conjunto liberado para mensagem"),
            Map.entry("group_approved", "Conjunto liberado para mensagem"),
            Map.entry("group.approval_invalidated", "Liberação do conjunto revogada após alteração"),
            Map.entry("group.split", "Conjunto separado"), Map.entry("group_split", "Conjunto separado"),
            Map.entry("group.merged", "Conjuntos unidos"), Map.entry("groups_merged", "Conjuntos unidos"),
            Map.entry("group.empty_removed", "Conjunto vazio retirado"),
            Map.entry("groups.selection_approved", "Conjuntos selecionados liberados"),
            Map.entry("groups.bulk_approved", "Conjuntos prontos liberados em sequência"),
            Map.entry("groups.client_approved", "Conjuntos do cliente liberados"),
            Map.entry("dispatch_composed", "Mensagem preparada"), Map.entry("dispatch_prepared", "Mensagem preparada"),
            Map.entry("dispatch_approved", "Mensagem aprovada"),
            Map.entry("dispatch_bulk_approved", "Mensagens aprovadas em lote"),
            Map.entry("dispatch_approval_invalidated", "Aprovação da mensagem invalidada"),
            Map.entry("dispatch_batch_paused", "Sequência pausada pelo operador"),
            Map.entry("provider_call_started", "Operação de e-mail iniciada"),
            Map.entry("provider_call_completed", "Operação de e-mail concluída"),
            Map.entry("dispatch_completed", "Operação de e-mail concluída"),
            Map.entry("provider_reconciled", "Resultado conferido"),
            Map.entry("dispatch_reconciled", "Resultado conferido"),
            Map.entry("email_provider_connected", "Conta de e-mail conectada"),
            Map.entry("email_provider_disconnected", "Conta de e-mail desconectada"),
            Map.entry("reports_exported", "Relatório exportado"));

    static String action(String action) {
        return ACTIONS.getOrDefault(action == null ? "" : action, fallback(action));
    }

    static String outcome(String outcome) {
        if (outcome == null || outcome.isBlank()) {
            return "Registrado";
        }
        var constant = outcome.strip().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
        for (var s : DispatchItemState.values()) {
            if (s.name().equals(constant)) {
                return state(s);
            }
        }
        for (var s : DeliveryAttemptState.values()) {
            if (s.name().equals(constant)) {
                return attempt(s);
            }
        }
        for (var s : ReviewDocumentState.values()) {
            if (s.name().equals(constant)) {
                return review(s);
            }
        }
        return switch (outcome.toLowerCase(Locale.ROOT)) {
            case "completed" -> "Concluído";
            case "pending" -> "Em processamento";
            case "failed" -> "Não concluído";
            case "cancelled" -> "Cancelado";
            case "paused" -> "Pausado";
            case "connected" -> "Conectado";
            case "disconnected" -> "Desconectado";
            default -> fallback(outcome);
        };
    }

    static String error(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        return switch (code) {
            case "FAKE_TRANSIENT_FAILURE" -> "O teste local simulou uma falha temporária.";
            case "FAKE_PERMANENT_FAILURE" -> "O teste local simulou uma falha permanente.";
            case "FAKE_TIMEOUT_AFTER_ACCEPTANCE" ->
                    "O teste ficou sem confirmação final e precisa ser conferido antes de repetir.";
            case "FAKE_AMBIGUOUS_RESULT" -> "O resultado do teste ficou incerto e precisa ser conferido antes de repetir.";
            case "PROVIDER_RESULT_UNKNOWN" -> "O serviço não confirmou o resultado; confira antes de repetir.";
            default -> "Consulte o suporte usando o código técnico recolhido nesta linha.";
        };
    }

    static String provider(String providerKey, String senderAccountId) {
        if (providerKey != null && !providerKey.isBlank()) {
            return providerKeyLabel(providerKey);
        }
        if (senderAccountId == null || senderAccountId.isBlank()) {
            return "Serviço ainda não definido";
        }
        var a = senderAccountId.strip().toLowerCase(Locale.ROOT);
        if (a.startsWith("google-gmail://") || a.equals("gmail") || a.equals("google.gmail")) {
            return "Google Gmail";
        }
        if (a.startsWith("microsoft-graph://") || a.equals("graph") || a.equals("microsoft.graph")) {
            return "Microsoft 365 / Outlook";
        }
        if (a.startsWith("fake://") || a.equals("fake.local")) {
            return "Simulação local (sem envio real)";
        }
        return "Conta configurada: " + senderAccountId.strip();
    }

    static String providerKeyLabel(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "google.gmail" -> "Google Gmail";
            case "microsoft.graph" -> "Microsoft 365 / Outlook";
            case "fake.local" -> "Simulação local (sem envio real)";
            default -> key;
        };
    }

    static String fallback(String value) {
        if (value == null) {
            return "Registrado";
        }
        var text = value.replace('_', ' ').replace('.', ' ').strip();
        return text.isEmpty() ? "Registrado" : text.substring(0, 1).toUpperCase(Locale.forLanguageTag("pt-BR"))
                + text.substring(1);
    }

    static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    static String shortHash(String hash) {
        return hash == null || hash.isBlank() ? "" : hash.substring(0, Math.min(12, hash.length()));
    }

    static String[] row(String... cells) {
        return Arrays.stream(cells).map(c -> c == null ? "" : c).toArray(String[]::new);
    }
}
