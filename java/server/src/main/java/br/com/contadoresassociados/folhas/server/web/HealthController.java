package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.server.db.Db;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /health/live} (processo) e {@code /health/ready} (banco), como no .NET. */
@RestController
public class HealthController {

    private final Db db;

    public HealthController(Db db) {
        this.db = db;
    }

    @GetMapping("/health/live")
    @Endpoint(anonymous = true, rate = RateLimiter.Policy.READ)
    public Map<String, String> live() {
        return Map.of("status", "Healthy");
    }

    @GetMapping("/health/ready")
    @Endpoint(anonymous = true, rate = RateLimiter.Policy.READ)
    public ResponseEntity<Map<String, String>> ready() {
        try {
            db.system(Db.Isolation.READ_COMMITTED, c -> {
                try (var st = c.prepareStatement("SELECT 1 FROM flyway_schema_history LIMIT 1")) {
                    st.executeQuery().close();
                }
                return null;
            });
            return ResponseEntity.ok(Map.of("status", "Healthy"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("status", "Unhealthy"));
        }
    }
}
