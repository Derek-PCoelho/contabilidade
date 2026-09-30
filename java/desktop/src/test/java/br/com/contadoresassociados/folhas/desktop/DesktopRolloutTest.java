package br.com.contadoresassociados.folhas.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.contadoresassociados.folhas.application.dispatch.DispatchWorkflowOptions;
import br.com.contadoresassociados.folhas.application.production.ProductionRollout;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Leitura das opções Phase11/Phase12: tudo desligado sem configuração, mesmas chaves do .NET. */
class DesktopRolloutTest {

    private static DesktopRollout of(Map<String, String> values) {
        return DesktopRollout.from(key -> Optional.ofNullable(values.get(key)));
    }

    @Test
    void closedAndHiddenWhenNothingIsConfigured() {
        var r = of(Map.of());
        assertThat(r.pilot().enabled()).isFalse();
        assertThat(r.pilot().allowSend()).isFalse();
        assertThat(r.production().stage()).isEqualTo(ProductionRollout.Stage.CLOSED);
        assertThat(r.readiness().readyForSend()).isFalse();
        assertThat(r.productionPanelVisible()).isFalse();
        assertThat(r.emailSendEnabled()).isFalse();
        var options = r.applyTo(DispatchWorkflowOptions.defaults());
        assertThat(options.pilotModeEnabled()).isFalse();
        assertThat(options.productionRolloutEnforced()).isTrue();
        assertThat(options.productionRolloutReady()).isFalse();
    }

    @Test
    void readsPilotAndProductionKeysAndIgnoresInvalidValues() {
        var r = of(Map.of("Phase11:Enabled", "true", "Phase11:MaximumClients", "3", "Phase11:AllowDraft", "talvez",
                "Phase12:Stage", "limited", "Phase12:MaximumBatchSize", "7", "Phase12:AllowedRoles", "Manager; Administrator"));
        assertThat(r.pilot().enabled()).isTrue();
        assertThat(r.pilot().maximumClients()).isEqualTo(3);
        assertThat(r.pilot().allowDraft()).isTrue();
        assertThat(r.production().stage()).isEqualTo(ProductionRollout.Stage.LIMITED);
        assertThat(r.production().allowedRoles()).containsExactlyInAnyOrder("Manager", "Administrator");
        assertThat(r.productionPanelVisible()).isTrue();
        // Piloto habilitado bloqueia a produção (o aceite formal exige o piloto encerrado).
        assertThat(r.readiness().blockers()).contains("O piloto supervisionado ainda não possui aceite operacional formal.");
        var options = r.applyTo(DispatchWorkflowOptions.defaults());
        assertThat(options.pilotModeEnabled()).isTrue();
        assertThat(options.productionMaximumBatchSize()).isEqualTo(7);
    }

    @Test
    void environmentNamesFollowBothConventions() {
        assertThat(DesktopRollout.environmentSetting("Phase11:Enabled")).isNotNull();
        // Sem variável definida no ambiente de teste, o valor fica vazio (padrão desligado).
        assertThat(DesktopRollout.environmentSetting("Phase99:Inexistente")).isEmpty();
    }
}
