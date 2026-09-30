package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migra o {@code cache.db}. O esquema base é idêntico às 7 migrations EF da versão .NET
 * (mesmos nomes de tabelas, colunas e índices, com o histórico em {@code __EFMigrationsHistory});
 * bancos criados pela versão .NET são reconhecidos e só recebem as migrations Java adicionais,
 * registradas em {@code java_schema_history}.
 */
final class LocalSchemaMigrator {

    static final String EF_PRODUCT_VERSION = "10.0.11";

    static final Map<String, List<String>> EF_MIGRATIONS = new LinkedHashMap<>();

    static {
        EF_MIGRATIONS.put("20260820225255_InitialLocalPhase2", List.of(
                """
                CREATE TABLE "cached_clients" ("Id" TEXT NOT NULL CONSTRAINT "PK_cached_clients" PRIMARY KEY,
                  "DisplayName" TEXT NOT NULL, "IsActive" INTEGER NOT NULL, "Version" INTEGER NOT NULL,
                  "UpdatedAtUtc" INTEGER NOT NULL)""",
                """
                CREATE TABLE "offline_sync_operations" ("OperationId" TEXT NOT NULL
                  CONSTRAINT "PK_offline_sync_operations" PRIMARY KEY, "ClientId" TEXT NOT NULL,
                  "DisplayName" TEXT NOT NULL, "IsActive" INTEGER NOT NULL, "ExpectedVersion" INTEGER NOT NULL,
                  "AttemptCount" INTEGER NOT NULL, "NextAttemptAtUtc" INTEGER NOT NULL, "CreatedAtUtc" INTEGER NOT NULL,
                  "IsConflicted" INTEGER NOT NULL, "LastErrorCode" TEXT NULL)""",
                """
                CREATE TABLE "sync_state" ("Id" INTEGER NOT NULL CONSTRAINT "PK_sync_state" PRIMARY KEY AUTOINCREMENT,
                  "Checkpoint" INTEGER NOT NULL)""",
                """
                CREATE INDEX "IX_offline_sync_operations_IsConflicted_NextAttemptAtUtc"
                  ON "offline_sync_operations" ("IsConflicted", "NextAttemptAtUtc")"""));
        EF_MIGRATIONS.put("20260821012514_AddPhase3CatalogCache", List.of(
                """
                CREATE TABLE "catalog_cache" ("RecordType" TEXT NOT NULL, "RecordId" TEXT NOT NULL,
                  "JsonPayload" TEXT NOT NULL, "Version" INTEGER NOT NULL, "UpdatedAtUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_catalog_cache" PRIMARY KEY ("RecordType", "RecordId"))""",
                """
                CREATE INDEX "IX_catalog_cache_RecordType_UpdatedAtUtc" ON "catalog_cache" ("RecordType", "UpdatedAtUtc")"""));
        EF_MIGRATIONS.put("20260821015659_AddPhase4DocumentRecognitionCache", List.of(
                """
                CREATE TABLE "document_recognition_cache" ("Sha256" TEXT NOT NULL
                  CONSTRAINT "PK_document_recognition_cache" PRIMARY KEY, "EngineVersion" TEXT NOT NULL,
                  "JsonPayload" TEXT NOT NULL, "RecognizedAtUtc" INTEGER NOT NULL)""",
                """
                CREATE INDEX "IX_document_recognition_cache_EngineVersion_RecognizedAtUtc"
                  ON "document_recognition_cache" ("EngineVersion", "RecognizedAtUtc")"""));
        EF_MIGRATIONS.put("20260821022858_AddPhase5DocumentReview", List.of(
                """
                CREATE TABLE "document_review_audit" ("ScopeKey" TEXT NOT NULL, "EventId" TEXT NOT NULL,
                  "Action" TEXT NOT NULL, "DocumentId" TEXT NULL, "GroupId" TEXT NULL, "JsonPayload" TEXT NOT NULL,
                  "TimestampUtc" INTEGER NOT NULL, CONSTRAINT "PK_document_review_audit" PRIMARY KEY ("ScopeKey", "EventId"))""",
                """
                CREATE TABLE "document_review_groups" ("ScopeKey" TEXT NOT NULL, "GroupId" TEXT NOT NULL,
                  "ClientId" TEXT NOT NULL, "PeriodKey" TEXT NOT NULL, "State" TEXT NOT NULL, "JsonPayload" TEXT NOT NULL,
                  "UpdatedAtUtc" INTEGER NOT NULL, CONSTRAINT "PK_document_review_groups" PRIMARY KEY ("ScopeKey", "GroupId"))""",
                """
                CREATE TABLE "document_reviews" ("ScopeKey" TEXT NOT NULL, "DocumentId" TEXT NOT NULL,
                  "Sha256" TEXT NOT NULL, "SemanticDuplicateKey" TEXT NOT NULL, "State" TEXT NOT NULL,
                  "JsonPayload" TEXT NOT NULL, "UpdatedAtUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_document_reviews" PRIMARY KEY ("ScopeKey", "DocumentId"))""",
                "CREATE INDEX \"IX_document_review_audit_ScopeKey_DocumentId\" ON \"document_review_audit\" (\"ScopeKey\", \"DocumentId\")",
                "CREATE INDEX \"IX_document_review_audit_ScopeKey_GroupId\" ON \"document_review_audit\" (\"ScopeKey\", \"GroupId\")",
                "CREATE INDEX \"IX_document_review_audit_ScopeKey_TimestampUtc\" ON \"document_review_audit\" (\"ScopeKey\", \"TimestampUtc\")",
                "CREATE INDEX \"IX_document_review_groups_ScopeKey_ClientId_PeriodKey\" ON \"document_review_groups\" (\"ScopeKey\", \"ClientId\", \"PeriodKey\")",
                "CREATE INDEX \"IX_document_reviews_ScopeKey_SemanticDuplicateKey\" ON \"document_reviews\" (\"ScopeKey\", \"SemanticDuplicateKey\")",
                "CREATE INDEX \"IX_document_reviews_ScopeKey_Sha256\" ON \"document_reviews\" (\"ScopeKey\", \"Sha256\")"));
        EF_MIGRATIONS.put("20260821131055_AddPhase6DispatchWorkflow", List.of(
                """
                CREATE TABLE "delivery_attempts" ("ScopeKey" TEXT NOT NULL, "AttemptId" TEXT NOT NULL,
                  "DispatchItemId" TEXT NOT NULL, "State" TEXT NOT NULL, "IdempotencyKey" TEXT NOT NULL,
                  "JsonPayload" TEXT NOT NULL, "StartedAtUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_delivery_attempts" PRIMARY KEY ("ScopeKey", "AttemptId"))""",
                """
                CREATE TABLE "dispatch_audit" ("ScopeKey" TEXT NOT NULL, "EventId" TEXT NOT NULL, "Action" TEXT NOT NULL,
                  "DispatchItemId" TEXT NULL, "JsonPayload" TEXT NOT NULL, "TimestampUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_dispatch_audit" PRIMARY KEY ("ScopeKey", "EventId"))""",
                """
                CREATE TABLE "dispatch_items" ("ScopeKey" TEXT NOT NULL, "DispatchItemId" TEXT NOT NULL,
                  "BatchId" TEXT NOT NULL, "GroupId" TEXT NOT NULL, "State" TEXT NOT NULL,
                  "DispatchFingerprint" TEXT NOT NULL, "JsonPayload" TEXT NOT NULL, "UpdatedAtUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_dispatch_items" PRIMARY KEY ("ScopeKey", "DispatchItemId"))""",
                """
                CREATE TABLE "processing_batches" ("ScopeKey" TEXT NOT NULL, "BatchId" TEXT NOT NULL, "State" TEXT NOT NULL,
                  "OperationMode" TEXT NOT NULL, "JsonPayload" TEXT NOT NULL, "UpdatedAtUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_processing_batches" PRIMARY KEY ("ScopeKey", "BatchId"))""",
                "CREATE INDEX \"IX_delivery_attempts_ScopeKey_DispatchItemId\" ON \"delivery_attempts\" (\"ScopeKey\", \"DispatchItemId\")",
                "CREATE UNIQUE INDEX \"IX_delivery_attempts_ScopeKey_IdempotencyKey\" ON \"delivery_attempts\" (\"ScopeKey\", \"IdempotencyKey\")",
                "CREATE INDEX \"IX_dispatch_audit_ScopeKey_DispatchItemId\" ON \"dispatch_audit\" (\"ScopeKey\", \"DispatchItemId\")",
                "CREATE INDEX \"IX_dispatch_audit_ScopeKey_TimestampUtc\" ON \"dispatch_audit\" (\"ScopeKey\", \"TimestampUtc\")",
                "CREATE INDEX \"IX_dispatch_items_ScopeKey_BatchId\" ON \"dispatch_items\" (\"ScopeKey\", \"BatchId\")",
                "CREATE INDEX \"IX_dispatch_items_ScopeKey_DispatchFingerprint\" ON \"dispatch_items\" (\"ScopeKey\", \"DispatchFingerprint\")",
                "CREATE INDEX \"IX_dispatch_items_ScopeKey_GroupId\" ON \"dispatch_items\" (\"ScopeKey\", \"GroupId\")",
                "CREATE INDEX \"IX_processing_batches_ScopeKey_UpdatedAtUtc\" ON \"processing_batches\" (\"ScopeKey\", \"UpdatedAtUtc\")"));
        EF_MIGRATIONS.put("20260821184110_AddPrePhase9WorkspacePreferences", List.of(
                """
                CREATE TABLE "workspace_preferences" ("Key" TEXT NOT NULL CONSTRAINT "PK_workspace_preferences" PRIMARY KEY,
                  "JsonPayload" TEXT NOT NULL, "UpdatedAtUtc" INTEGER NOT NULL)"""));
        EF_MIGRATIONS.put("20260821194242_AddPhase9Incidents", List.of(
                """
                CREATE TABLE "incident_audit" ("ScopeKey" TEXT NOT NULL, "EventId" TEXT NOT NULL, "IncidentId" TEXT NOT NULL,
                  "Action" TEXT NOT NULL, "JsonPayload" TEXT NOT NULL, "TimestampUtc" INTEGER NOT NULL,
                  CONSTRAINT "PK_incident_audit" PRIMARY KEY ("ScopeKey", "EventId"))""",
                """
                CREATE TABLE "incidents" ("ScopeKey" TEXT NOT NULL, "IncidentId" TEXT NOT NULL,
                  "DeliveryAttemptId" TEXT NOT NULL, "Status" TEXT NOT NULL, "Severity" TEXT NOT NULL,
                  "Version" INTEGER NOT NULL, "JsonPayload" TEXT NOT NULL, "DetectedAtUtc" INTEGER NOT NULL,
                  "UpdatedAtUtc" INTEGER NOT NULL, CONSTRAINT "PK_incidents" PRIMARY KEY ("ScopeKey", "IncidentId"))""",
                "CREATE INDEX \"IX_incident_audit_ScopeKey_IncidentId_TimestampUtc\" ON \"incident_audit\" (\"ScopeKey\", \"IncidentId\", \"TimestampUtc\")",
                "CREATE INDEX \"IX_incidents_ScopeKey_DeliveryAttemptId\" ON \"incidents\" (\"ScopeKey\", \"DeliveryAttemptId\")",
                "CREATE INDEX \"IX_incidents_ScopeKey_Status_UpdatedAtUtc\" ON \"incidents\" (\"ScopeKey\", \"Status\", \"UpdatedAtUtc\")"));
    }

    /** Migrations exclusivas da versão Java (aditivas; o app .NET ignora tabelas extras). */
    static final Map<String, List<String>> JAVA_MIGRATIONS = new LinkedHashMap<>();

    static {
        JAVA_MIGRATIONS.put("J001_workspace_versions", List.of(
                // Pendência 3.13: versão por workspace e escopo para concorrência otimista.
                """
                CREATE TABLE IF NOT EXISTS "workspace_versions" ("ScopeKey" TEXT NOT NULL, "Workspace" TEXT NOT NULL,
                  "Version" INTEGER NOT NULL, CONSTRAINT "PK_workspace_versions" PRIMARY KEY ("ScopeKey", "Workspace"))"""));
    }

    void migrate(LocalDatabase db) {
        db.transaction(c -> {
            exec(c, """
                    CREATE TABLE IF NOT EXISTS "__EFMigrationsHistory" ("MigrationId" TEXT NOT NULL
                      CONSTRAINT "PK___EFMigrationsHistory" PRIMARY KEY, "ProductVersion" TEXT NOT NULL)""");
            exec(c, """
                    CREATE TABLE IF NOT EXISTS "java_schema_history" ("MigrationId" TEXT NOT NULL PRIMARY KEY,
                      "AppliedAtUtc" TEXT NOT NULL)""");
            for (var entry : EF_MIGRATIONS.entrySet()) {
                if (!applied(c, "__EFMigrationsHistory", entry.getKey())) {
                    for (var sql : entry.getValue()) {
                        exec(c, sql);
                    }
                    try (var ps = c.prepareStatement(
                            "INSERT INTO \"__EFMigrationsHistory\" (\"MigrationId\", \"ProductVersion\") VALUES (?, ?)")) {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, EF_PRODUCT_VERSION);
                        ps.executeUpdate();
                    }
                }
            }
            for (var entry : JAVA_MIGRATIONS.entrySet()) {
                if (!applied(c, "java_schema_history", entry.getKey())) {
                    for (var sql : entry.getValue()) {
                        exec(c, sql);
                    }
                    try (var ps = c.prepareStatement(
                            "INSERT INTO \"java_schema_history\" (\"MigrationId\", \"AppliedAtUtc\") VALUES (?, ?)")) {
                        ps.setString(1, entry.getKey());
                        ps.setString(2, java.time.Instant.now().toString());
                        ps.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    private static boolean applied(Connection c, String table, String id) throws SQLException {
        try (var ps = c.prepareStatement("SELECT 1 FROM \"" + table + "\" WHERE \"MigrationId\" = ?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (var st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
