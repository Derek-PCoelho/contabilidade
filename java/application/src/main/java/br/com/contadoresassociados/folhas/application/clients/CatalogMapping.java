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
import br.com.contadoresassociados.folhas.domain.clients.Client;
import br.com.contadoresassociados.folhas.domain.clients.ClientCatalogDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierSemanticRole;
import br.com.contadoresassociados.folhas.domain.clients.ClientIdentifierType;
import br.com.contadoresassociados.folhas.domain.clients.ClientPartnerDraft;
import br.com.contadoresassociados.folhas.domain.clients.ClientPartnerRole;
import br.com.contadoresassociados.folhas.domain.clients.DeliveryRole;
import br.com.contadoresassociados.folhas.domain.clients.EstablishmentDraft;
import br.com.contadoresassociados.folhas.domain.clients.MessageTemplate;
import br.com.contadoresassociados.folhas.domain.clients.MessageTemplateDraft;
import br.com.contadoresassociados.folhas.domain.clients.PersonType;
import br.com.contadoresassociados.folhas.domain.clients.RecipientDraft;
import br.com.contadoresassociados.folhas.domain.clients.SignatureMode;
import br.com.contadoresassociados.folhas.domain.common.DomainValidationException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;

/** Conversões domínio ⇄ contratos do catálogo (mesma ordenação do .NET). */
public final class CatalogMapping {

    private CatalogMapping() {
    }

    public static ClientDetails map(Client client) {
        return new ClientDetails(client.id(), PersonTypeModel.valueOf(client.personType().name()),
                client.legalNameOrFullName(), client.preferredName(), client.internalCode(),
                client.primaryTaxIdNormalized(), client.active(), client.defaultSubjectTemplateId(),
                client.defaultBodyTemplateId(), client.notes(), client.version(), utc(client.createdAtUtc()),
                utc(client.updatedAtUtc()),
                client.identifiers().stream().sorted(Comparator.comparingInt(i -> i.priority()))
                        .map(i -> new ClientIdentifierModel(i.id(), ClientIdentifierTypeModel.valueOf(i.type().name()),
                                i.valueNormalized(), ClientIdentifierSemanticRoleModel.valueOf(i.semanticRole().name()),
                                i.priority(), i.active(), i.uniqueWithinOrganization())).toList(),
                client.establishments().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.Establishment e) -> !e.headOffice())
                        .thenComparing(e -> e.displayName() == null ? "" : e.displayName()))
                        .map(e -> new EstablishmentModel(e.id(), e.cnpjNormalized(), e.legalName(), e.displayName(),
                                e.internalCode(), e.headOffice(), e.active())).toList(),
                client.recipients().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.Recipient r) -> r.deliveryRole())
                        .thenComparing(r -> r.displayName() == null ? "" : r.displayName()))
                        .map(r -> new RecipientModel(r.id(), r.establishmentId(), r.displayName(), r.emailNormalized(),
                                DeliveryRoleModel.valueOf(r.deliveryRole().name()), r.documentTypeId(), r.primary(),
                                r.active(), r.validFrom(), r.validTo())).toList(),
                client.partners().stream().sorted(Comparator
                        .comparing((br.com.contadoresassociados.folhas.domain.clients.ClientPartner p) -> p.role())
                        .thenComparing(p -> p.fullName() == null ? "" : p.fullName()))
                        .map(p -> new ClientPartnerModel(p.id(), p.fullName(), p.cpfNormalized(),
                                ClientPartnerRoleModel.valueOf(p.role().name()), p.active(), p.emailNormalized()))
                        .toList());
    }

    public static MessageTemplateModel map(MessageTemplate t) {
        return new MessageTemplateModel(t.id(), t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), SignatureModeModel.valueOf(t.signatureMode().name()), t.isDefault(), t.active(),
                t.version(), utc(t.updatedAtUtc()));
    }

    public static ClientCatalogDraft toDomain(ClientMutationRequest r) {
        return new ClientCatalogDraft(PersonType.valueOf(r.personType().name()), r.legalNameOrFullName(),
                r.preferredName(), r.internalCode(), r.primaryTaxId(), r.isActive(), r.defaultSubjectTemplateId(),
                r.defaultBodyTemplateId(), r.notes(), r.identifiers().stream().map(CatalogMapping::toDomain)
                        .toList(),
                r.establishments().stream().map(CatalogMapping::toDomain).toList(),
                r.recipients().stream().map(CatalogMapping::toDomain).toList(),
                r.partners().stream().map(CatalogMapping::toDomain).toList());
    }

    public static ClientCatalogDraft toDomain(ClientDetails c) {
        return new ClientCatalogDraft(PersonType.valueOf(c.personType().name()), c.legalNameOrFullName(),
                c.preferredName(), c.internalCode(), c.primaryTaxId(), c.isActive(), c.defaultSubjectTemplateId(),
                c.defaultBodyTemplateId(), c.notes(), c.identifiers().stream().map(CatalogMapping::toDomain)
                        .toList(),
                c.establishments().stream().map(CatalogMapping::toDomain).toList(),
                c.recipients().stream().map(CatalogMapping::toDomain).toList(),
                c.partners().stream().map(CatalogMapping::toDomain).toList());
    }

    public static ClientIdentifierDraft toDomain(ClientIdentifierModel i) {
        return new ClientIdentifierDraft(i.id(), ClientIdentifierType.valueOf(i.type().name()), i.value(),
                ClientIdentifierSemanticRole.valueOf(i.semanticRole().name()), i.priority(), i.isActive(),
                i.isUniqueWithinOrganization());
    }

    public static EstablishmentDraft toDomain(EstablishmentModel e) {
        return new EstablishmentDraft(e.id(), e.cnpj(), e.legalName(), e.displayName(), e.internalCode(),
                e.isHeadOffice(), e.isActive());
    }

    public static RecipientDraft toDomain(RecipientModel r) {
        return new RecipientDraft(r.id(), r.establishmentId(), r.displayName(), r.email(),
                DeliveryRole.valueOf(r.deliveryRole().name()), r.documentTypeId(), r.isPrimary(), r.isActive(),
                r.validFrom(), r.validTo());
    }

    public static ClientPartnerDraft toDomain(ClientPartnerModel p) {
        return new ClientPartnerDraft(p.id(), p.fullName(), p.cpf(), ClientPartnerRole.valueOf(p.role().name()),
                p.isActive(), p.email());
    }

    public static MessageTemplateDraft toDomain(MessageTemplateMutationRequest r) {
        return new MessageTemplateDraft(r.clientId(), r.documentTypeId(), r.name(), r.subjectTemplate(),
                r.bodyTemplate(), SignatureMode.valueOf(r.signatureMode().name()), r.isDefault(), r.isActive());
    }

    public static MessageTemplateDraft toDomain(MessageTemplateModel t) {
        return new MessageTemplateDraft(t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), SignatureMode.valueOf(t.signatureMode().name()), t.isDefault(), t.isActive());
    }

    public static MessageTemplateMutationRequest toMutation(MessageTemplateModel t, boolean active) {
        return new MessageTemplateMutationRequest(t.version(), t.clientId(), t.documentTypeId(), t.name(),
                t.subjectTemplate(), t.bodyTemplate(), t.signatureMode(), t.isDefault(), active);
    }

    public static ClientListItem toListItem(ClientDetails client) {
        String masked;
        try {
            masked = BrazilianRegistration.mask(client.primaryTaxId());
        } catch (DomainValidationException e) {
            masked = "***";
        }
        return new ClientListItem(client.id(), client.personType(), displayName(client), masked, client.internalCode(),
                client.isActive(), client.version(), (int) client.establishments().stream()
                        .filter(EstablishmentModel::isActive).count(),
                (int) client.recipients().stream().filter(RecipientModel::isActive).count(), client.updatedAtUtc());
    }

    public static String displayName(ClientDetails client) {
        return client.preferredName() != null && !client.preferredName().isBlank() ? client.preferredName()
                : client.legalNameOrFullName();
    }

    public static ClientDetails withMeta(ClientDetails c, long version, OffsetDateTime createdAt,
            OffsetDateTime updatedAt, boolean active) {
        return new ClientDetails(c.id(), c.personType(), c.legalNameOrFullName(), c.preferredName(), c.internalCode(),
                c.primaryTaxId(), active, c.defaultSubjectTemplateId(), c.defaultBodyTemplateId(), c.notes(), version,
                createdAt, updatedAt, c.identifiers(), c.establishments(), c.recipients(), c.partners());
    }

    public static MessageTemplateModel withMeta(MessageTemplateModel t, long version, OffsetDateTime updatedAt) {
        return new MessageTemplateModel(t.id(), t.clientId(), t.documentTypeId(), t.name(), t.subjectTemplate(),
                t.bodyTemplate(), t.signatureMode(), t.isDefault(), t.isActive(), version, updatedAt);
    }

    public static ClientDetails copy(ClientDetails c, java.util.List<ClientIdentifierModel> identifiers,
            java.util.List<RecipientModel> recipients, java.util.List<ClientPartnerModel> partners) {
        return new ClientDetails(c.id(), c.personType(), c.legalNameOrFullName(), c.preferredName(), c.internalCode(),
                c.primaryTaxId(), c.isActive(), c.defaultSubjectTemplateId(), c.defaultBodyTemplateId(), c.notes(),
                c.version(), c.createdAtUtc(), c.updatedAtUtc(), identifiers, c.establishments(), recipients, partners);
    }

    public static ClientMutationRequest copy(ClientMutationRequest r, java.util.List<ClientIdentifierModel> identifiers,
            java.util.List<RecipientModel> recipients, java.util.List<ClientPartnerModel> partners) {
        return new ClientMutationRequest(r.expectedVersion(), r.personType(), r.legalNameOrFullName(),
                r.preferredName(), r.internalCode(), r.primaryTaxId(), r.isActive(), r.defaultSubjectTemplateId(),
                r.defaultBodyTemplateId(), r.notes(), identifiers, r.establishments(), recipients, partners);
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
