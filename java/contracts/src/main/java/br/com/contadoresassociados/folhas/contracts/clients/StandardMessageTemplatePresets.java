package br.com.contadoresassociados.folhas.contracts.clients;

import static br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog.*;

import java.util.List;

/** Modelos padrão sugeridos para PJ e PF (mesmo texto da versão .NET). */
public final class StandardMessageTemplatePresets {

    public record Preset(PersonTypeModel personType, String name, String subjectTemplate, String bodyTemplate) {
    }

    private static final Preset LEGAL = create(PersonTypeModel.LEGAL_ENTITY, "Padrão para empresa",
            toToken(CONTACT_NAME));
    private static final Preset INDIVIDUAL = create(PersonTypeModel.INDIVIDUAL, "Padrão para pessoa física",
            toToken(CLIENT_PREFERRED_OR_LEGAL_NAME));

    private StandardMessageTemplatePresets() {
    }

    public static List<Preset> all() {
        return List.of(LEGAL, INDIVIDUAL);
    }

    public static Preset forType(PersonTypeModel type) {
        return type == PersonTypeModel.LEGAL_ENTITY ? LEGAL : INDIVIDUAL;
    }

    private static Preset create(PersonTypeModel type, String name, String salutation) {
        var client = toToken(CLIENT_PREFERRED_OR_LEGAL_NAME);
        var period = toToken(PERIOD_LABEL);
        return new Preset(type, name, "Documentos contábeis - " + client + " - " + period,
                "Olá, " + salutation + ",\n\n"
                        + "Encaminhamos em anexo os documentos referentes a " + period + ":\n" + toToken(DOCUMENT_LIST)
                        + "\n\n" + "Vencimentos identificados:\n" + toToken(DUE_DATE_LIST) + "\n\n"
                        + "Pedimos, por gentileza, que confira os anexos. Permanecemos à disposição em caso de dúvidas.\n\n"
                        + "Atenciosamente,\n" + toToken(OFFICE_NAME));
    }
}
