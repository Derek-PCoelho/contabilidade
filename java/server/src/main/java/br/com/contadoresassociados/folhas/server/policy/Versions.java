package br.com.contadoresassociados.folhas.server.policy;

import java.util.Arrays;
import java.util.Optional;

/**
 * Comparação de versões no formato aceito pelo {@code System.Version.TryParse} do .NET
 * (2 a 4 partes numéricas), tolerando sufixo SemVer ({@code 1.2.3-beta}), que é ignorado na
 * comparação numérica como na versão .NET (a política usa apenas os números).
 */
public record Versions(int[] parts, String text) implements Comparable<Versions> {

    public static Optional<Versions> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var text = value.strip();
        if (text.isEmpty() || text.length() > 32) {
            return Optional.empty();
        }
        var numeric = text.split("[-+]", 2)[0];
        var pieces = numeric.split("\\.");
        if (pieces.length < 2 || pieces.length > 4) {
            return Optional.empty();
        }
        var parts = new int[4];
        for (var i = 0; i < pieces.length; i++) {
            if (!pieces[i].matches("\\d{1,9}")) {
                return Optional.empty();
            }
            parts[i] = Integer.parseInt(pieces[i]);
        }
        return Optional.of(new Versions(parts, text));
    }

    public String normalized() {
        return parts[0] + "." + parts[1] + "." + parts[2];
    }

    @Override
    public int compareTo(Versions o) {
        return Arrays.compare(parts, o.parts);
    }

    public boolean isAtLeast(Versions minimum) {
        return compareTo(minimum) >= 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Versions v && Arrays.equals(parts, v.parts);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(parts);
    }

    @Override
    public String toString() {
        return text;
    }
}
