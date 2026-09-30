package br.com.contadoresassociados.folhas.application.clients;

import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierSemanticRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientListItem;
import br.com.contadoresassociados.folhas.contracts.clients.ClientMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateMutationRequest;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.clients.SignatureModeModel;
import br.com.contadoresassociados.folhas.domain.clients.BrazilianRegistration;
import br.com.contadoresassociados.folhas.domain.clients.ClientOperationalReadiness;
import br.com.contadoresassociados.folhas.domain.clients.EmailAddress;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Regras do catálogo comuns ao perfil Local e ao servidor: unicidade fiscal (CPF, CNPJ, raiz,
 * estabelecimento), código contábil, identificadores únicos, modelos padrão e a compatibilidade
 * com cadastros antigos (e-mails inválidos, identificador fiscal no tipo de pessoa errado).
 * Mensagens em português com código estável ({@link CatalogException}).
 */
public final class CatalogRules {

    private CatalogRules() {
    }

    public static CatalogException invalid(String message) {
        return new CatalogException("catalog.invalid", message);
    }


    public static void validateImportedCatalog(List<ClientDetails> clients, List<MessageTemplateModel> templates) {
        for (var i = 0; i < clients.size(); i++) {
            validateClientUniqueness(clients.get(i), clients.subList(i + 1, clients.size()));
        }
        var clientIds = clients.stream().map(ClientDetails::id).collect(Collectors.toSet());
        if (templates.stream().anyMatch(t -> t.clientId() != null && !clientIds.contains(t.clientId()))) {
            throw CatalogRules.invalid("Todo modelo importado deve referenciar um cliente existente.");
        }
        var activeTemplateIds = templates.stream().filter(MessageTemplateModel::isActive).map(MessageTemplateModel::id)
                .collect(Collectors.toSet());
        for (var client : clients) {
            validateTemplateReferences(client.defaultSubjectTemplateId(), client.defaultBodyTemplateId(), templates,
                    activeTemplateIds);
        }
        var names = new HashSet<String>();
        for (var t : templates) {
            if (!names.add(t.clientId() + "|" + t.name().strip().toLowerCase(Locale.ROOT))) {
                throw CatalogRules.invalid("A cópia criaria modelos com nomes repetidos no mesmo cadastro.");
            }
        }
        var defaults = new HashSet<String>();
        for (var t : templates) {
            if (t.isActive() && t.isDefault() && !defaults.add(t.clientId() + "|" + t.documentTypeId())) {
                throw CatalogRules.invalid("Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
            }
        }
    }

    public static void validateClientUniqueness(ClientDetails candidate, List<ClientDetails> others) {
        var otherTax = others.stream().flatMap(o -> taxIdentities(o).stream()).collect(Collectors.toSet());
        if (taxIdentities(candidate).stream().anyMatch(otherTax::contains)) {
            throw new CatalogException.Duplicate("CPF, CNPJ, raiz empresarial ou estabelecimento");
        }
        var otherCodes = others.stream().flatMap(o -> internalCodes(o).stream()).collect(Collectors.toSet());
        if (internalCodes(candidate).stream().anyMatch(otherCodes::contains)) {
            throw new CatalogException.Duplicate("código contábil");
        }
        var candidateUnique = comparable(candidate).filter(ClientIdentifierModel::isUniqueWithinOrganization)
                .map(CatalogRules::identifierKey).collect(Collectors.toSet());
        var candidateAll = comparable(candidate).map(CatalogRules::identifierKey)
                .collect(Collectors.toSet());
        var otherUnique = others.stream().flatMap(CatalogRules::comparable)
                .filter(ClientIdentifierModel::isUniqueWithinOrganization)
                .map(CatalogRules::identifierKey).collect(Collectors.toSet());
        var otherAll = others.stream().flatMap(CatalogRules::comparable)
                .map(CatalogRules::identifierKey).collect(Collectors.toSet());
        if (candidateUnique.stream().anyMatch(otherAll::contains) || otherUnique.stream().anyMatch(candidateAll::contains)) {
            throw new CatalogException.Duplicate("identificador");
        }
    }

    private static String identifierKey(ClientIdentifierModel identifier) {
        return identifier.type() + "|" + identifier.value();
    }

    public static Set<String> taxIdentities(ClientDetails client) {
        var values = new HashSet<String>();
        var tax = client.primaryTaxId() == null ? "" : client.primaryTaxId();
        if (client.personType() == PersonTypeModel.INDIVIDUAL) {
            values.add("cpf|" + tax);
        } else {
            values.add("cnpj|" + tax);
            if (tax.length() >= BrazilianRegistration.CNPJ_ROOT_LENGTH) {
                values.add("cnpj-root|" + tax.substring(0, BrazilianRegistration.CNPJ_ROOT_LENGTH));
            }
        }
        for (var identifier : client.identifiers()) {
            var v = identifier.value() == null ? "" : identifier.value();
            switch (identifier.type()) {
                case CPF -> {
                    if (client.personType() == PersonTypeModel.INDIVIDUAL) {
                        values.add("cpf|" + v);
                    }
                }
                case CNPJ -> {
                    if (client.personType() == PersonTypeModel.LEGAL_ENTITY) {
                        values.add("cnpj|" + v);
                        if (v.length() >= 8) {
                            values.add("cnpj-root|" + v.substring(0, 8));
                        }
                    }
                }
                case CNPJ_ROOT -> {
                    if (client.personType() == PersonTypeModel.LEGAL_ENTITY) {
                        values.add("cnpj-root|" + v);
                    }
                }
                default -> {
                    // demais identificadores não são fiscais
                }
            }
        }
        for (var establishment : client.establishments()) {
            var cnpj = establishment.cnpj() == null ? "" : establishment.cnpj();
            values.add("cnpj|" + cnpj);
            if (cnpj.length() >= 8) {
                values.add("cnpj-root|" + cnpj.substring(0, 8));
            }
        }
        return values;
    }

    private static Stream<ClientIdentifierModel> comparable(ClientDetails client) {
        return client.identifiers().stream().filter(i -> !isMisplacedTaxIdentifier(client.personType(), i.type()));
    }

    public static Set<String> internalCodes(ClientDetails client) {
        var values = new HashSet<String>();
        if (client.internalCode() != null && !client.internalCode().isBlank()) {
            values.add(client.internalCode().toLowerCase(Locale.ROOT));
        }
        client.identifiers().stream().filter(i -> i.type() == ClientIdentifierTypeModel.INTERNAL_CODE)
                .forEach(i -> values.add(i.value().toLowerCase(Locale.ROOT)));
        client.establishments().stream().filter(e -> e.internalCode() != null && !e.internalCode().isBlank())
                .forEach(e -> values.add(e.internalCode().toLowerCase(Locale.ROOT)));
        return values;
    }

    public static void validateTemplateReferences(UUID subject, UUID body, List<MessageTemplateModel> templates,
            Set<UUID> activeIds) {
        var requested = Stream.of(subject, body).filter(id -> id != null && !id.equals(new UUID(0, 0))).distinct()
                .toList();
        if (requested.isEmpty()) {
            return;
        }
        var active = activeIds != null ? activeIds : templates.stream().filter(MessageTemplateModel::isActive)
                .map(MessageTemplateModel::id).collect(Collectors.toSet());
        if (requested.stream().anyMatch(id -> !active.contains(id))) {
            throw CatalogRules.invalid("Os modelos padrão devem existir e estar ativos.");
        }
    }

    public static void validateTemplateUniqueness(UUID templateId, MessageTemplateMutationRequest request,
            List<MessageTemplateModel> templates) {
        var others = templates.stream().filter(t -> !t.id().equals(templateId)).toList();
        var name = request.name() == null ? "" : request.name().strip();
        if (others.stream().anyMatch(t -> Objects.equals(t.clientId(), request.clientId())
                && t.name().equalsIgnoreCase(name))) {
            throw new CatalogException.Duplicate("nome de modelo neste cadastro");
        }
        if (request.isActive() && request.isDefault() && others.stream().anyMatch(t -> t.isActive() && t.isDefault()
                && Objects.equals(t.clientId(), request.clientId())
                && Objects.equals(t.documentTypeId(), request.documentTypeId()))) {
            throw CatalogRules.invalid("Só pode existir um modelo padrão ativo para o mesmo cliente e tipo de documento.");
        }
    }

    // ---------------------------------------------------------------- compatibilidade com dados legados

    public static boolean isMisplacedTaxIdentifier(PersonTypeModel personType, ClientIdentifierTypeModel type) {
        return switch (personType) {
            case LEGAL_ENTITY -> type == ClientIdentifierTypeModel.CPF;
            case INDIVIDUAL -> type == ClientIdentifierTypeModel.CNPJ || type == ClientIdentifierTypeModel.CNPJ_ROOT;
        };
    }

    private static List<ClientIdentifierModel> quarantine(PersonTypeModel personType,
            List<ClientIdentifierModel> identifiers) {
        return identifiers.stream().map(i -> isMisplacedTaxIdentifier(personType, i.type()) && i.isActive()
                ? new ClientIdentifierModel(i.id(), i.type(), i.value(), i.semanticRole(), i.priority(), false,
                        i.isUniqueWithinOrganization())
                : i).toList();
    }

    public static ClientDetails quarantineMisplacedTaxIdentifiers(ClientDetails client) {
        return CatalogMapping.copy(client, quarantine(client.personType(), client.identifiers()), client.recipients(),
                client.partners());
    }

    public static ClientMutationRequest quarantineLegacyMisplacedTaxIdentifiers(ClientDetails existing,
            ClientMutationRequest request) {
        var legacyIds = existing.identifiers().stream()
                .filter(i -> isMisplacedTaxIdentifier(existing.personType(), i.type())).map(ClientIdentifierModel::id)
                .collect(Collectors.toSet());
        var identifiers = request.identifiers().stream().map(i -> legacyIds.contains(i.id())
                && isMisplacedTaxIdentifier(request.personType(), i.type()) && i.isActive()
                ? new ClientIdentifierModel(i.id(), i.type(), i.value(), i.semanticRole(), i.priority(), false,
                        i.isUniqueWithinOrganization())
                : i).toList();
        return CatalogMapping.copy(request, identifiers, request.recipients(), request.partners());
    }

    public static boolean validOptionalEmail(String email) {
        return email == null || email.isBlank() || EmailAddress.isValid(email);
    }

    public static ClientDetails prepareLegacyEmailDataForMutation(ClientDetails existing, ClientMutationRequest request) {
        var recipients = existing.recipients().stream().filter(r -> EmailAddress.isValid(r.email())
                || request.recipients().stream().anyMatch(q -> q.id().equals(r.id())
                        && (q.isActive() || EmailAddress.isValid(q.email()))))
                .map(r -> {
                    if (EmailAddress.isValid(r.email())) {
                        return r;
                    }
                    var replacement = request.recipients().stream().filter(q -> q.id().equals(r.id())).findFirst()
                            .orElseThrow();
                    return EmailAddress.isValid(replacement.email()) ? replacement : r;
                }).toList();
        var partners = existing.partners().stream().filter(p -> validOptionalEmail(p.email())
                || request.partners().stream().anyMatch(q -> q.id().equals(p.id())
                        && (q.isActive() || validOptionalEmail(q.email()))))
                .map(p -> {
                    if (validOptionalEmail(p.email())) {
                        return p;
                    }
                    var replacement = request.partners().stream().filter(q -> q.id().equals(p.id())).findFirst()
                            .orElseThrow();
                    return validOptionalEmail(replacement.email()) ? replacement : p;
                }).toList();
        return CatalogMapping.copy(existing, existing.identifiers(), recipients, partners);
    }

    public static ClientMutationRequest prepareLegacyEmailRequestForMutation(ClientDetails existing,
            ClientMutationRequest request) {
        var invalidRecipients = existing.recipients().stream().filter(r -> !EmailAddress.isValid(r.email()))
                .map(RecipientModel::id).collect(Collectors.toSet());
        var invalidPartners = existing.partners().stream().filter(p -> !validOptionalEmail(p.email()))
                .map(ClientPartnerModel::id).collect(Collectors.toSet());
        var recipients = request.recipients().stream().filter(r -> !invalidRecipients.contains(r.id()) || r.isActive()
                || EmailAddress.isValid(r.email())).toList();
        var partners = request.partners().stream().filter(p -> !invalidPartners.contains(p.id()) || p.isActive()
                || validOptionalEmail(p.email())).toList();
        return CatalogMapping.copy(request, request.identifiers(), recipients, partners);
    }

    public static ClientDetails removeInactiveLegacyInvalidEmails(ClientDetails client) {
        return CatalogMapping.copy(client, client.identifiers(),
                client.recipients().stream().filter(r -> r.isActive() || EmailAddress.isValid(r.email())).toList(),
                client.partners().stream().filter(p -> p.isActive() || validOptionalEmail(p.email())).toList());
    }

    public static List<String> legacyEmailReadinessBlocks(ClientDetails client, LocalDate today) {
        var blocks = new java.util.LinkedHashSet<String>();
        if (!client.isActive()) {
            blocks.add(ClientOperationalReadiness.CLIENT_INACTIVE);
        }
        if (client.recipients().stream().anyMatch(r -> r.isActive() && !EmailAddress.isValid(r.email()))) {
            blocks.add(ClientOperationalReadiness.RECIPIENT_EMAIL_INVALID);
        }
        if (client.recipients().stream().noneMatch(r -> r.deliveryRole() == DeliveryRoleModel.TO && r.isActive()
                && (r.validFrom() == null || !r.validFrom().isAfter(today))
                && (r.validTo() == null || !r.validTo().isBefore(today)) && EmailAddress.isValid(r.email()))) {
            blocks.add(ClientOperationalReadiness.NO_ACTIVE_TO_RECIPIENT);
        }
        if (client.partners().stream().anyMatch(p -> p.isActive() && !validOptionalEmail(p.email()))) {
            blocks.add(ClientOperationalReadiness.PARTNER_EMAIL_INVALID);
        }
        return List.copyOf(blocks);
    }
}
