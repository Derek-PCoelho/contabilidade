package br.com.contadoresassociados.folhas.application.documents;

import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriodKind;
import br.com.contadoresassociados.folhas.contracts.documents.FindingResolutionType;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedDocumentType;
import br.com.contadoresassociados.folhas.contracts.documents.RecognizedField;
import br.com.contadoresassociados.folhas.contracts.documents.SemanticFieldRole;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationFinding;
import br.com.contadoresassociados.folhas.contracts.documents.ValidationSeverity;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Regras de validação de documento (mesmas da versão .NET, com as correções do relatório). */
public final class ValidationRules {

    private ValidationRules() {
    }

    public static List<ValidationRule> defaults(DocumentReviewOptions options) {
        return List.of(new FileIntegrity(), new Recognition(), new ClientResolution(), new RequiredFields(),
                new Period(), new Amount(), new EmployerRootConsistency(), new DueDate(options),
                new PageCompleteness());
    }

    static ValidationFinding finding(DocumentValidationContext ctx, String code, ValidationSeverity severity,
            String message, String fieldKey) {
        return new ValidationFinding(UUID.randomUUID(), code, severity, message, fieldKey, false,
                FindingResolutionType.NONE, null, null, ctx.evaluatedAtUtc(), null);
    }

    /** Integridade do arquivo, com cache de hash por tamanho e data (pendência 2.7). */
    public static final class FileIntegrity implements ValidationRule {
        @Override
        public String code() {
            return "document.file_integrity";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var path = Path.of(ctx.document().localPath());
            if (!Files.exists(path)) {
                return List.of(finding(ctx, "document.file_missing", ValidationSeverity.BLOCKER,
                        "O arquivo local não está mais disponível. Selecione novamente o PDF correto.", null));
            }
            try {
                if (Files.size(path) == 0) {
                    return List.of(finding(ctx, "document.file_empty", ValidationSeverity.BLOCKER,
                            "O arquivo está vazio.", null));
                }
                var hash = ctx.fingerprints().sha256(path);
                return hash.equalsIgnoreCase(ctx.document().sha256()) ? List.of()
                        : List.of(finding(ctx, "document.hash_changed", ValidationSeverity.BLOCKER,
                                "O conteúdo do PDF mudou depois do reconhecimento; a aprovação anterior não é válida.",
                                null));
            } catch (AccessDeniedException e) {
                return List.of(finding(ctx, "document.file_access_denied", ValidationSeverity.BLOCKER,
                        "O acesso ao arquivo foi negado durante a conferência de integridade.", null));
            } catch (IOException e) {
                return List.of(finding(ctx, "document.file_unreadable", ValidationSeverity.BLOCKER,
                        "O arquivo não pôde ser relido para confirmar sua integridade.", null));
            }
        }
    }

    public static final class Recognition implements ValidationRule {
        @Override
        public String code() {
            return "document.recognition";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var out = new ArrayList<ValidationFinding>();
            if (ctx.document().documentType() == RecognizedDocumentType.UNCLASSIFIED) {
                out.add(finding(ctx, "document.unclassified", ValidationSeverity.BLOCKER,
                        "O tipo documental não foi reconhecido com segurança.", null));
            }
            for (var f : ctx.document().recognitionFindings()) {
                out.add(finding(ctx, f.code(), f.isBlocker() ? ValidationSeverity.BLOCKER : ValidationSeverity.WARNING,
                        f.message(), null));
            }
            return out;
        }
    }

    public static final class ClientResolution implements ValidationRule {
        @Override
        public String code() {
            return "document.client_resolution";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var out = new ArrayList<ValidationFinding>();
            var doc = ctx.document();
            if (doc.clientId() == null) {
                out.add(finding(ctx, "client.not_resolved", ValidationSeverity.BLOCKER,
                        "O cliente não foi resolvido de forma inequívoca.", null));
            }
            if (doc.pendingClientChangeFrom() != null) {
                out.add(finding(ctx, "client.assignment_changed", ValidationSeverity.BLOCKER,
                        message("client.assignment_changed"), null));
            }
            doc.resolutionBlockers().stream().distinct()
                    .filter(c -> !c.equals("client.not_resolved") && !c.equals("client.assignment_changed"))
                    .forEach(c -> out.add(finding(ctx, c, ValidationSeverity.BLOCKER, message(c), null)));
            return out;
        }

        static String message(String code) {
            return switch (code) {
                case "client.inactive" -> "O cadastro de cliente ou estabelecimento está inativo.";
                case "client.cnpj_root_ambiguous" -> "A raiz de CNPJ corresponde a mais de um cliente ativo.";
                case "client.assignment_changed" ->
                        "O cadastro passou a indicar outro cliente. Confira e confirme a associação manualmente.";
                case "client.resolution_offline" -> "A resolução autenticada do cliente está indisponível offline.";
                case "client.resolution_authentication_required" -> "Entre na sua conta para resolver o cliente.";
                case "client.resolution_rate_limited" ->
                        "O servidor pediu uma pausa nas consultas. Aguarde alguns segundos e revalide.";
                default -> "O cliente não pôde ser confirmado automaticamente. Confira o cadastro e os dados reconhecidos.";
            };
        }
    }

    public static final class RequiredFields implements ValidationRule {
        @Override
        public String code() {
            return "document.required_fields";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            if (ctx.profile() == null) {
                return List.of(finding(ctx, "profile.validation_missing", ValidationSeverity.BLOCKER,
                        "Não há perfil de validação publicado para este tipo documental.", null));
            }
            return ctx.profile().requiredRoles().stream().sorted()
                    .filter(role -> ctx.document().fields().stream()
                            .noneMatch(f -> f.role() == role && f.value() != null && !f.value().isBlank()))
                    .map(role -> finding(ctx, "field.required." + role.name().toLowerCase(Locale.ROOT),
                            ValidationSeverity.ERROR,
                            "O campo obrigatório \"" + DocumentPresentation.label(role) + "\" não foi encontrado no PDF.",
                            role.name()))
                    .toList();
        }
    }

    public static final class Period implements ValidationRule {
        @Override
        public String code() {
            return "document.period";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var p = ctx.document().period();
            if (ctx.profile() != null && ctx.profile().requirePeriod() && p.kind() == DocumentPeriodKind.UNKNOWN) {
                return List.of(finding(ctx, "period.missing_or_invalid", ValidationSeverity.ERROR,
                        "A competência ou o período obrigatório não pôde ser normalizado.", "Period"));
            }
            if (p.startDate() != null && p.endDate() != null && p.endDate().isBefore(p.startDate())) {
                return List.of(finding(ctx, "period.range_inverted", ValidationSeverity.BLOCKER,
                        "A data final do período é anterior à data inicial.", "Period"));
            }
            return List.of();
        }
    }

    public static final class Amount implements ValidationRule {
        @Override
        public String code() {
            return "document.amount";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            if (ctx.profile() == null || !ctx.profile().requirePositiveAmount()) {
                return List.of();
            }
            var field = ctx.document().fields().stream().filter(f -> f.role() == SemanticFieldRole.TOTAL_AMOUNT)
                    .findFirst();
            if (field.isEmpty()) {
                return List.of();
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(field.get().value().strip());
            } catch (NumberFormatException e) {
                return List.of(finding(ctx, "amount.unparseable", ValidationSeverity.ERROR,
                        "O valor total extraído não é um número válido.", field.get().name()));
            }
            if (amount.signum() < 0) {
                return List.of(finding(ctx, "amount.negative_unexpected", ValidationSeverity.BLOCKER,
                        "O total extraído é negativo em um perfil que exige valor positivo.", field.get().name()));
            }
            if (amount.signum() == 0) {
                return List.of(finding(ctx, "amount.zero_unexpected", ValidationSeverity.ERROR,
                        "O total extraído é zero e exige revisão contábil.", field.get().name()));
            }
            return List.of();
        }
    }

    /** Raízes de CNPJ diferentes no mesmo documento. Aceita CNPJ alfanumérico (pendência 1.1). */
    public static final class EmployerRootConsistency implements ValidationRule {
        @Override
        public String code() {
            return "document.employer_roots";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var roots = ctx.document().fields().stream()
                    .filter(f -> f.role() == SemanticFieldRole.EMPLOYER_TAX_ID
                            || f.role() == SemanticFieldRole.ESTABLISHMENT_TAX_ID)
                    .map(RecognizedField::value)
                    .map(BrazilianRegistration::tryNormalizeCnpj)
                    .filter(BrazilianRegistration.Result::isValid)
                    .map(r -> r.normalized().substring(0, 8))
                    .distinct().count();
            return roots > 1 ? List.of(finding(ctx, "client.multiple_employer_roots", ValidationSeverity.BLOCKER,
                    "O documento contém empregadores de raízes CNPJ diferentes.", null)) : List.of();
        }
    }

    public static final class DueDate implements ValidationRule {
        private final DocumentReviewOptions options;

        public DueDate(DocumentReviewOptions options) {
            this.options = options;
        }

        @Override
        public String code() {
            return "document.due_date";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var due = ctx.document().period().dueDate();
            return options.pastDueDateProducesWarning() && due != null && due.isBefore(ctx.accountingDate())
                    ? List.of(finding(ctx, "due_date.past", ValidationSeverity.WARNING,
                            "O vencimento informado no documento já passou; confirme a situação antes de prosseguir.",
                            "DueDate"))
                    : List.of();
        }
    }

    /**
     * Pendência 10.14 (página faltante): se o PDF declara "Página X de Y" e Y é maior que as
     * páginas existentes, o documento está incompleto.
     */
    public static final class PageCompleteness implements ValidationRule {
        @Override
        public String code() {
            return "document.page_completeness";
        }

        @Override
        public List<ValidationFinding> evaluate(DocumentValidationContext ctx) {
            var declared = ctx.document().recognitionFindings().stream()
                    .filter(f -> f.code().equals("document.declared_pages"))
                    .map(f -> f.message().replaceAll("\\D", ""))
                    .filter(s -> !s.isEmpty()).map(Integer::parseInt).findFirst();
            if (declared.isPresent() && declared.get() > ctx.document().pageCount()) {
                return List.of(finding(ctx, "document.pages_missing", ValidationSeverity.BLOCKER,
                        "O PDF indica " + declared.get() + " páginas, mas só tem " + ctx.document().pageCount()
                                + ". Verifique se o arquivo está completo.", null));
            }
            return List.of();
        }
    }
}
