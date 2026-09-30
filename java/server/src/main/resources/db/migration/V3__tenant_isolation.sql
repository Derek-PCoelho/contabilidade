-- 3.6: defesa em profundidade por organização (equivalente ao HasQueryFilter que faltava).
-- Toda transação de requisição autenticada executa SET LOCAL folhas.organization_id; com o valor
-- definido, o PostgreSQL só devolve e só aceita linhas daquela organização, mesmo que uma consulta
-- esqueça o filtro. Operações de sistema (migração, provisionamento, login) não definem o valor.
CREATE FUNCTION folhas_current_organization() RETURNS uuid
    LANGUAGE sql STABLE PARALLEL SAFE
    AS $$ SELECT NULLIF(current_setting('folhas.organization_id', true), '')::uuid $$;

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'clients', 'client_identifiers', 'client_establishments', 'client_recipients', 'client_partners',
        'message_templates', 'sync_clients', 'sync_changes', 'sync_operations', 'sync_counters',
        'audit_events', 'device_sessions', 'production_dispatch_authorizations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (folhas_current_organization() IS NULL OR "OrganizationId" = folhas_current_organization()) '
            || 'WITH CHECK (folhas_current_organization() IS NULL OR "OrganizationId" = folhas_current_organization())',
            table_name);
    END LOOP;
END $$;
