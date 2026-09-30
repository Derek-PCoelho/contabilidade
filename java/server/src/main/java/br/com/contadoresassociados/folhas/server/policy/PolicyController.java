package br.com.contadoresassociados.folhas.server.policy;

import br.com.contadoresassociados.folhas.contracts.policy.AppReleasePolicyResponse;
import br.com.contadoresassociados.folhas.contracts.policy.PilotPolicyResponse;
import br.com.contadoresassociados.folhas.contracts.policy.ProductionPolicyResponse;
import br.com.contadoresassociados.folhas.contracts.policy.ServiceStatusResponse;
import br.com.contadoresassociados.folhas.server.dispatch.PreflightService;
import br.com.contadoresassociados.folhas.server.web.ApiException;
import br.com.contadoresassociados.folhas.server.web.Endpoint;
import br.com.contadoresassociados.folhas.server.web.RateLimiter.Policy;
import br.com.contadoresassociados.folhas.server.web.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Políticas de versão, piloto e produção gradual (mesmas rotas e regras do .NET). */
@RestController
public class PolicyController {

    private final RolloutPolicies policies;
    private final PreflightService preflight;

    public PolicyController(RolloutPolicies policies, PreflightService preflight) {
        this.policies = policies;
        this.preflight = preflight;
    }

    @GetMapping("/")
    @Endpoint(anonymous = true, rate = Policy.READ)
    public ServiceStatusResponse status() {
        return new ServiceStatusResponse("Folhas da Michelly API", "Phase12GradualProduction");
    }

    @GetMapping("/api/app-release-policy")
    @Endpoint(rate = Policy.READ)
    public AppReleasePolicyResponse release(@RequestParam String currentVersion) {
        var current = Versions.parse(currentVersion)
                .orElseThrow(() -> ApiException.badRequest("request.invalid", "Versão do aplicativo inválida."));
        var minimum = Versions.parse(policies.minimumSupportedVersion())
                .orElseThrow(() -> new ApiException(503, "policy.misconfigured",
                        "A política de versões do servidor está mal configurada."));
        return new AppReleasePolicyResponse(minimum.text(), current.isAtLeast(minimum), policies.globalSendEnabled(),
                policies.betaChannelEnabled(), policies.stableChannelEnabled());
    }

    @GetMapping("/api/pilot/policy")
    @Endpoint(rate = Policy.READ)
    public PilotPolicyResponse pilot() {
        var pilot = policies.pilot();
        var minimum = policies.minimumSupportedVersion();
        if (!pilot.enabled() || !pilot.allowTest() || !pilot.allowDraft() || pilot.allowSend()
                || !pilot.requireNonProductionData()
                || !"staging".equals(pilot.environmentName().toLowerCase(Locale.ROOT))
                || pilot.maximumClients() < 1 || pilot.maximumClients() > 5 || Versions.parse(minimum).isEmpty()) {
            throw new ApiException(503, "pilot.unavailable", "O piloto supervisionado não está disponível.");
        }
        return new PilotPolicyResponse(true, pilot.environmentName(), true, true, false, true, pilot.maximumClients(),
                minimum);
    }

    @GetMapping("/api/production/policy")
    @Endpoint(permissions = "email.send", mfa = true, rate = Policy.READ)
    public ProductionPolicyResponse production(HttpServletRequest http) {
        var user = RequestContext.require(http);
        var options = policies.production();
        var readiness = policies.productionReadiness();
        var today = preflight.authorizedToday(user.organizationId());
        var roleAllowed = readiness.allowedRoles().stream().anyMatch(user.roles()::contains);
        var stage = options.stage().name().charAt(0) + options.stage().name().substring(1).toLowerCase(Locale.ROOT);
        return new ProductionPolicyResponse(options.enabled(), options.environmentName(), stage,
                readiness.readyForSend(), roleAllowed,
                readiness.readyForSend() && roleAllowed && today < options.maximumDailySends(),
                options.maximumBatchSize(), options.maximumDailySends(), today,
                Math.max(0, options.maximumDailySends() - today), options.minimumApplicationVersion(),
                readiness.allowedRoles(), readiness.blockers());
    }
}
