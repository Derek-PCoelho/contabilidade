package br.com.contadoresassociados.folhas.desktop.ui;

import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import javafx.util.StringConverter;

/** Rótulos em português para os enums exibidos (equivalente ao {@code FriendlyTextConverter}). */
public final class FriendlyText {

    private FriendlyText() {
    }

    public static String of(Object value) {
        return switch (value) {
            case null -> "";
            case PersonTypeModel p -> switch (p) {
                case INDIVIDUAL -> "Pessoa física";
                case LEGAL_ENTITY -> "Empresa";
            };
            case ClientPartnerRoleModel r -> switch (r) {
                case MANAGING_PARTNER -> "Sócio-administrador";
                case PARTNER -> "Sócio";
                case ADMINISTRATOR -> "Administrador";
                case LEGAL_REPRESENTATIVE -> "Representante legal";
                case OTHER -> "Outro vínculo";
            };
            case ClientIdentifierTypeModel t -> switch (t) {
                case CNPJ -> "CNPJ";
                case CNPJ_ROOT -> "Raiz do CNPJ";
                case CPF -> "CPF";
                case INTERNAL_CODE -> "Código do sistema contábil";
                case LEGAL_NAME_ALIAS -> "Outro nome conhecido";
                case OTHER -> "Outro identificador";
            };
            case DeliveryRoleModel d -> switch (d) {
                case TO -> "Destinatário principal";
                case CC -> "Receber em cópia";
                case INTERNAL_COPY -> "Cópia interna do escritório";
            };
            default -> value.toString();
        };
    }

    public static <T> StringConverter<T> converter() {
        return new StringConverter<>() {
            @Override
            public String toString(T object) {
                return of(object);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
