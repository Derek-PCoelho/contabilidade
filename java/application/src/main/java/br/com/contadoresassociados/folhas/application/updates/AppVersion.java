package br.com.contadoresassociados.folhas.application.updates;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Versão SemVer (major.minor.patch[-pre]) com comparação correta de pré-release. */
public record AppVersion(int major, int minor, int patch, String preRelease) implements Comparable<AppVersion> {

    // SEMVER precisa ser inicializado antes de CURRENT (ordem de inicialização estática).
    private static final Pattern SEMVER = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?$");

    /** Versão desta build; o empacotamento substitui via recurso {@code /app-version.txt}. */
    public static final AppVersion CURRENT = loadCurrent();

    public static Optional<AppVersion> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        var m = SEMVER.matcher(value.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new AppVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4)));
    }

    public boolean isAtLeast(AppVersion minimum) {
        return compareTo(minimum) >= 0;
    }

    @Override
    public int compareTo(AppVersion o) {
        var c = Integer.compare(major, o.major);
        if (c == 0) {
            c = Integer.compare(minor, o.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, o.patch);
        }
        if (c != 0) {
            return c;
        }
        if (Objects.equals(preRelease, o.preRelease)) {
            return 0;
        }
        if (preRelease == null) {
            return 1;
        }
        if (o.preRelease == null) {
            return -1;
        }
        return preRelease.compareTo(o.preRelease);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }

    private static AppVersion loadCurrent() {
        try (var in = AppVersion.class.getResourceAsStream("/app-version.txt")) {
            if (in != null) {
                var text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                return parse(text).orElse(new AppVersion(1, 0, 0, null));
            }
        } catch (java.io.IOException ignored) {
            // versão padrão abaixo
        }
        return new AppVersion(1, 0, 0, null);
    }
}
