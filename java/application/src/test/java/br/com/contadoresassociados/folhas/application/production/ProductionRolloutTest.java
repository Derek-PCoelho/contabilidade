package br.com.contadoresassociados.folhas.application.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductionRolloutTest {

    @Test
    void closedByDefault() {
        var r = ProductionRollout.evaluate(ProductionRollout.Options.closed(), false, false, false);
        assertThat(r.readyForSend()).isFalse();
        assertThat(r.blockers()).contains("A etapa de produção permanece fechada.");
    }

    @Test
    void readyWhenAllControlsApprovedAndRejectsNonPrivilegedRoles() {
        var ok = new ProductionRollout.Options(true, true, "Production", ProductionRollout.Stage.LIMITED, true, true,
                true, true, true, true, true, true, 5, 20, "1.0.0", Set.of("Manager"));
        assertThat(ProductionRollout.evaluate(ok, false, true, true).readyForSend()).isTrue();
        var bad = new ProductionRollout.Options(true, true, "production", ProductionRollout.Stage.LIMITED, true, true,
                true, true, true, true, true, true, 5, 20, "1.0.0", Set.of("Manager", "Operator"));
        assertThat(ProductionRollout.evaluate(bad, false, true, true).readyForSend()).isFalse();
        var tooNew = new ProductionRollout.Options(true, true, "production", ProductionRollout.Stage.LIMITED, true,
                true, true, true, true, true, true, true, 5, 20, "9.0.0", Set.of("Manager"));
        assertThat(ProductionRollout.evaluate(tooNew, false, true, true).blockers())
                .anyMatch(b -> b.contains("abaixo do mínimo"));
    }
}
