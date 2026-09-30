package br.com.contadoresassociados.folhas.server.web;

import br.com.contadoresassociados.folhas.application.common.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Janela fixa de 1 minuto por partição, como o {@code FixedWindowRateLimiter} do .NET
 * (autenticação 10, leitura 240, escrita 60, sensível 20, global 600).
 */
public final class RateLimiter {

    public enum Policy {
        GLOBAL(600), AUTHENTICATION(10), READ(240), WRITE(60), SENSITIVE(20);

        private final int permits;

        Policy(int permits) {
            this.permits = permits;
        }

        public int permits() {
            return permits;
        }
    }

    private record Window(long minute, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Retorna os segundos até a próxima janela quando o limite estourou; 0 quando liberado. */
    public long acquire(Policy policy, String partition) {
        var millis = clock.now().toEpochMilli();
        var minute = millis / 60_000;
        var key = policy.name() + ":" + partition;
        var window = windows.compute(key, (k, w) -> w == null || w.minute != minute
                ? new Window(minute, new AtomicInteger()) : w);
        if (window.count.incrementAndGet() > policy.permits()) {
            return Math.max(1, 60 - (millis / 1000) % 60);
        }
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute < minute);
        }
        return 0;
    }
}
