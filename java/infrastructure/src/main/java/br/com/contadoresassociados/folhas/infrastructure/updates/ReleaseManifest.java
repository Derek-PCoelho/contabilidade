package br.com.contadoresassociados.folhas.infrastructure.updates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manifesto de release publicado no feed ({@code releases-{canal}.json}) e assinado com Ed25519
 * ({@code releases-{canal}.json.sig}, assinatura em base64 sobre os bytes exatos do JSON).
 *
 * <p>{@code sequence} cresce a cada publicação e {@code expiresAtUtc} limita a validade: assim um
 * manifesto antigo, ainda que assinado, não pode ser reapresentado para forçar downgrade ou
 * esconder uma correção de segurança (ataque de replay/freeze).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseManifest(
        int formatVersion,
        String channel,
        long sequence,
        OffsetDateTime publishedAtUtc,
        OffsetDateTime expiresAtUtc,
        String version,
        String minimumSupportedVersion,
        String notes,
        List<Artifact> artifacts) {

    public static final int FORMAT_VERSION = 1;

    public ReleaseManifest {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    /** Pacote por plataforma ({@code win-x64}, {@code osx-arm64}...). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(String platform, String fileName, String sha256, long sizeBytes, String kind) {
    }
}
