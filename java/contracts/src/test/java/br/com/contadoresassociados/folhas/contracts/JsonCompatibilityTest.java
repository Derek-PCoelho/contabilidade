package br.com.contadoresassociados.folhas.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchItemState;
import br.com.contadoresassociados.folhas.contracts.dispatch.DispatchWorkspace;
import br.com.contadoresassociados.folhas.contracts.dispatch.MessageTemplatePlaceholderCatalog;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriod;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentPeriodKind;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class JsonCompatibilityTest {

    /** Payload no formato gravado pela versão .NET (JsonSerializerDefaults.Web: camelCase, enums numéricos). */
    @Test
    void readsDotNetWebJson() {
        var json = """
            {"id":"6f1c2b9e-1111-4a0e-8e44-000000000001","personType":1,"legalNameOrFullName":"Empresa",
             "preferredName":null,"internalCode":"C1","primaryTaxId":"11222333000181","isActive":true,
             "defaultSubjectTemplateId":null,"defaultBodyTemplateId":null,"notes":null,"version":3,
             "createdAtUtc":"2026-08-21T10:00:00+00:00","updatedAtUtc":"2026-08-21T10:05:00.1234567+00:00",
             "identifiers":[],"establishments":[],"recipients":[
               {"id":"6f1c2b9e-1111-4a0e-8e44-000000000002","establishmentId":null,"displayName":"Fin",
                "email":"fin@example.com","deliveryRole":0,"documentTypeId":null,"isPrimary":true,"isActive":true,
                "validFrom":"2026-01-01","validTo":null}],
             "partners":null,"unknownFutureField":42}
            """;
        var details = Json.read(json, ClientDetails.class);
        assertThat(details.personType()).isEqualTo(PersonTypeModel.LEGAL_ENTITY);
        assertThat(details.recipients()).singleElement().satisfies(r -> {
            assertThat(r.validFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(r.isPrimary()).isTrue();
        });
        assertThat(details.partners()).isEmpty();
        assertThat(details.updatedAtUtc().getNano()).isEqualTo(123_456_700);
        var roundTrip = Json.read(Json.write(details), ClientDetails.class);
        assertThat(roundTrip).isEqualTo(details);
        assertThat(Json.write(details)).contains("\"personType\":1").contains("\"isActive\":true");
    }

    @Test
    void oldWorkspaceWithoutVersionStillLoads() {
        var ws = Json.read("{\"scopeKey\":\"s\",\"batches\":[],\"items\":[],\"attempts\":[],\"auditEvents\":[]}",
                DispatchWorkspace.class);
        assertThat(ws.version()).isZero();
        assertThat(DispatchItemState.values()[4]).isEqualTo(DispatchItemState.DRAFT_CREATED);
    }

    @Test
    void periodLabelsAreCultureIndependent() {
        var p = DocumentPeriod.monthly(3, 2026);
        assertThat(p.displayLabel()).isEqualTo("03/2026");
        assertThat(p.canonicalKey()).isEqualTo("month:2026-03");
        var assessment = new DocumentPeriod(DocumentPeriodKind.ASSESSMENT_PERIOD, null, null,
                LocalDate.of(2026, 8, 31), null, null, null);
        assertThat(assessment.groupingLabel()).isEqualTo("08/2026");
        assertThat(assessment.displayLabel()).isEqualTo("31/08/2026");
    }

    @Test
    void placeholderValidationDetectsUnknownAndMalformed() {
        assertThat(MessageTemplatePlaceholderCatalog.validate("Olá {{contato.nome}} - {{ periodo.rotulo }}").isValid())
                .isTrue();
        assertThat(MessageTemplatePlaceholderCatalog.validate("{{competencia}}").unknownKeys())
                .containsExactly("competencia");
        assertThat(MessageTemplatePlaceholderCatalog.validate("Olá {{contato.nome").isValid()).isFalse();
        assertThat(MessageTemplatePlaceholderCatalog.replaceTokens("A {{contato.nome}} $1", k -> "Ana $")).isEqualTo(
                "A Ana $ $1");
    }
}
