package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import br.com.contadoresassociados.folhas.application.common.Clock;
import br.com.contadoresassociados.folhas.application.documents.recognition.RecognitionPorts;
import br.com.contadoresassociados.folhas.contracts.documents.ClientResolutionResult;
import br.com.contadoresassociados.folhas.contracts.documents.DocumentRecognitionResult;
import br.com.contadoresassociados.folhas.contracts.json.Json;
import java.util.Optional;

/** Cache por SHA-256; a resolução de cliente nunca é persistida (é refeita com o usuário autenticado). */
public final class SqliteRecognitionCache implements RecognitionPorts.RecognitionCache {

    private final LocalDatabase db;
    private final Clock clock;

    public SqliteRecognitionCache(LocalDatabase db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    @Override
    public Optional<DocumentRecognitionResult> get(String sha256, String engineVersion) {
        return db.read(c -> {
            try (var ps = c.prepareStatement("""
                    SELECT "JsonPayload" FROM "document_recognition_cache" WHERE "Sha256" = ? AND "EngineVersion" = ?""")) {
                ps.setString(1, sha256);
                ps.setString(2, engineVersion);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(Json.read(rs.getString(1), DocumentRecognitionResult.class))
                            : Optional.<DocumentRecognitionResult>empty();
                }
            }
        });
    }

    @Override
    public void put(DocumentRecognitionResult r, String engineVersion) {
        var sanitized = new DocumentRecognitionResult(r.fileName(), r.sha256(), r.mimeType(), r.fileSizeBytes(),
                r.pageCount(), r.documentType(), r.profileVersion(), r.confidence(), r.confidenceScore(), r.needsOcr(),
                false, r.fields(), ClientResolutionResult.unresolved("client.resolution_requires_authenticated_refresh"),
                r.findings());
        db.transaction(c -> {
            try (var ps = c.prepareStatement("""
                    INSERT INTO "document_recognition_cache" ("Sha256", "EngineVersion", "JsonPayload", "RecognizedAtUtc")
                    VALUES (?, ?, ?, ?) ON CONFLICT ("Sha256") DO UPDATE SET "EngineVersion" = excluded."EngineVersion",
                      "JsonPayload" = excluded."JsonPayload", "RecognizedAtUtc" = excluded."RecognizedAtUtc\"""")) {
                ps.setString(1, r.sha256());
                ps.setString(2, engineVersion);
                ps.setString(3, Json.write(sanitized));
                ps.setLong(4, SqliteCodec.dateTimeOffset(clock.nowUtc()));
                return ps.executeUpdate();
            }
        });
    }
}
