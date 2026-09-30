-- Correções de integridade do banco central (análise, seção 3).

-- 3.1: contador por organização. A linha é travada (FOR UPDATE) no início de toda transação que
-- grava no catálogo; o checkpoint (sequence global) só é obtido depois da trava, então dentro de
-- uma organização a ordem dos checkpoints é a ordem de commit e o pull nunca pula mudanças.
CREATE TABLE sync_counters (
    "OrganizationId" uuid NOT NULL,
    "LastCheckpoint" bigint NOT NULL DEFAULT 0,
    "UpdatedAtUtc" timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT "PK_sync_counters" PRIMARY KEY ("OrganizationId")
);
INSERT INTO sync_counters ("OrganizationId", "LastCheckpoint")
SELECT "OrganizationId", max("Checkpoint") FROM sync_changes GROUP BY "OrganizationId";

-- 3.3: índices únicos com colunas anuláveis passam a tratar NULL como valor (PostgreSQL 15+).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM client_recipients
        GROUP BY "ClientId", "EstablishmentId", "EmailNormalized", "DeliveryRole", "DocumentTypeId"
        HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Há destinatários duplicados (mesmo cliente, estabelecimento, e-mail, papel e tipo de documento). Remova as duplicatas antes de atualizar o servidor.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM message_templates
        GROUP BY "OrganizationId", "ClientId", "Name"
        HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'Há modelos de mensagem com o mesmo nome no mesmo escopo. Renomeie as duplicatas antes de atualizar o servidor.';
    END IF;
END $$;
DROP INDEX "IX_client_recipients_ClientId_EstablishmentId_EmailNormalized_~";
CREATE UNIQUE INDEX "IX_client_recipients_ClientId_EstablishmentId_EmailNormalized_~"
    ON client_recipients ("ClientId", "EstablishmentId", "EmailNormalized", "DeliveryRole", "DocumentTypeId")
    NULLS NOT DISTINCT;
-- 4.19: arquivamento no servidor (o registro sai das listas, mas continua no histórico e nas FKs).
-- Clientes arquivados continuam reservando CPF/CNPJ/códigos (os índices únicos dos filhos já
-- incluíam registros inativos na versão .NET); o nome de um modelo arquivado pode ser reutilizado.
ALTER TABLE clients ADD COLUMN "ArchivedAtUtc" timestamp with time zone NULL;
ALTER TABLE clients ADD COLUMN "ArchivedBy" uuid NULL;
ALTER TABLE clients ADD COLUMN "ArchiveReason" character varying(500) NULL;
ALTER TABLE message_templates ADD COLUMN "ArchivedAtUtc" timestamp with time zone NULL;
ALTER TABLE message_templates ADD COLUMN "ArchivedBy" uuid NULL;
ALTER TABLE message_templates ADD COLUMN "ArchiveReason" character varying(500) NULL;

DROP INDEX "IX_message_templates_OrganizationId_ClientId_Name";
CREATE UNIQUE INDEX "IX_message_templates_OrganizationId_ClientId_Name"
    ON message_templates ("OrganizationId", "ClientId", "Name") NULLS NOT DISTINCT
    WHERE "ArchivedAtUtc" IS NULL;
-- Um único modelo padrão ativo por (cliente, tipo de documento) também no banco.
CREATE UNIQUE INDEX "UX_message_templates_active_default"
    ON message_templates ("OrganizationId", "ClientId", "DocumentTypeId") NULLS NOT DISTINCT
    WHERE "IsActive" AND "IsDefault" AND "ArchivedAtUtc" IS NULL;

-- 3.4: chaves estrangeiras ausentes. As referências cruzadas cliente <-> modelo são adiáveis
-- (conferidas no commit), pois um cliente pode apontar para um modelo próprio criado junto.
-- Entram NOT VALID (valem para toda gravação nova) e são
-- validadas em seguida; se houver dado legado órfão, a validação vira aviso em vez de travar.
ALTER TABLE users ADD CONSTRAINT "FK_users_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE clients ADD CONSTRAINT "FK_clients_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE clients ADD CONSTRAINT "FK_clients_message_templates_DefaultSubjectTemplateId"
    FOREIGN KEY ("DefaultSubjectTemplateId") REFERENCES message_templates ("Id") ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE clients ADD CONSTRAINT "FK_clients_message_templates_DefaultBodyTemplateId"
    FOREIGN KEY ("DefaultBodyTemplateId") REFERENCES message_templates ("Id") ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE message_templates ADD CONSTRAINT "FK_message_templates_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE message_templates ADD CONSTRAINT "FK_message_templates_clients_ClientId"
    FOREIGN KEY ("ClientId") REFERENCES clients ("Id") ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED NOT VALID;
ALTER TABLE client_recipients ADD CONSTRAINT "FK_client_recipients_client_establishments_EstablishmentId"
    FOREIGN KEY ("EstablishmentId") REFERENCES client_establishments ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE device_sessions ADD CONSTRAINT "FK_device_sessions_users_UserId"
    FOREIGN KEY ("UserId") REFERENCES users ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE device_sessions ADD CONSTRAINT "FK_device_sessions_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE audit_events ADD CONSTRAINT "FK_audit_events_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE sync_clients ADD CONSTRAINT "FK_sync_clients_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE sync_changes ADD CONSTRAINT "FK_sync_changes_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE sync_operations ADD CONSTRAINT "FK_sync_operations_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE sync_counters ADD CONSTRAINT "FK_sync_counters_organizations_OrganizationId"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE production_dispatch_authorizations ADD CONSTRAINT "FK_production_dispatch_authorizations_organizations"
    FOREIGN KEY ("OrganizationId") REFERENCES organizations ("Id") ON DELETE RESTRICT NOT VALID;
ALTER TABLE production_dispatch_authorizations ADD CONSTRAINT "FK_production_dispatch_authorizations_users"
    FOREIGN KEY ("UserId") REFERENCES users ("Id") ON DELETE RESTRICT NOT VALID;

DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT conrelid::regclass AS table_name, conname
        FROM pg_constraint
        WHERE contype = 'f' AND NOT convalidated AND connamespace = current_schema()::regnamespace
    LOOP
        BEGIN
            EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I', item.table_name, item.conname);
        EXCEPTION WHEN foreign_key_violation THEN
            RAISE WARNING 'Dados legados violam % em %; a regra vale para novas gravações. Corrija os registros órfãos e rode VALIDATE CONSTRAINT.',
                item.conname, item.table_name;
        END;
    END LOOP;
END $$;

-- 3.8: busca sem acento e com índice trigram.
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
DO $do$
DECLARE
    ext_schema text := (SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                        WHERE e.extname = 'unaccent');
BEGIN
    -- o dicionário é qualificado com o esquema da extensão para a função ser IMMUTABLE de fato
    -- (independente do search_path) e poder ser usada no índice
    EXECUTE 'CREATE FUNCTION folhas_search_key(value text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT '
        || 'AS ' || quote_literal('SELECT upper(regexp_replace(' || quote_ident(ext_schema) || '.unaccent('
        || quote_literal(ext_schema || '.unaccent') || '::regdictionary, value), ''\s+'', '' '', ''g''))');
END $do$;
CREATE INDEX "IX_clients_search_legal_name_trgm" ON clients
    USING gin (folhas_search_key("LegalNameOrFullName") gin_trgm_ops);
CREATE INDEX "IX_clients_search_preferred_name_trgm" ON clients
    USING gin (folhas_search_key("PreferredName") gin_trgm_ops);
CREATE INDEX "IX_clients_tax_id_trgm" ON clients USING gin ("PrimaryTaxIdNormalized" gin_trgm_ops);

-- 3.12: auditoria por entidade.
CREATE INDEX "IX_audit_events_OrganizationId_EntityType_EntityId_TimestampUtc"
    ON audit_events ("OrganizationId", "EntityType", "EntityId", "TimestampUtc" DESC);

-- Append-only também no banco (antes só o SaveChanges impedia alteração).
CREATE FUNCTION folhas_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A tabela % aceita somente inclusões.', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER "TR_audit_events_append_only" BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION folhas_reject_mutation();
CREATE TRIGGER "TR_production_dispatch_authorizations_append_only" BEFORE UPDATE OR DELETE
    ON production_dispatch_authorizations FOR EACH ROW EXECUTE FUNCTION folhas_reject_mutation();

-- Sessões de dispositivo: listagem por usuária (4.16).
CREATE INDEX "IX_device_sessions_UserId_LastSeenAtUtc" ON device_sessions ("UserId", "LastSeenAtUtc" DESC);
