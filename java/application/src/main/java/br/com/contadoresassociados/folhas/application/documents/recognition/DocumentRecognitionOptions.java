package br.com.contadoresassociados.folhas.application.documents.recognition;

import java.time.Duration;

/** Limites de extração (mesmos padrões do .NET). */
public record DocumentRecognitionOptions(long maximumFileSizeBytes, int maximumPageCount, int maximumExtractedCharacters,
        Duration extractionTimeout) {

    public static final String ENGINE_VERSION = "phase4-v1";

    public static DocumentRecognitionOptions defaults() {
        return new DocumentRecognitionOptions(25L * 1024 * 1024, 100, 2_000_000, Duration.ofSeconds(20));
    }
}
