package br.com.contadoresassociados.folhas.server.catalog;

import br.com.contadoresassociados.folhas.contracts.clients.ClientDetails;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierSemanticRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientIdentifierTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerModel;
import br.com.contadoresassociados.folhas.contracts.clients.ClientPartnerRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.DeliveryRoleModel;
import br.com.contadoresassociados.folhas.contracts.clients.EstablishmentModel;
import br.com.contadoresassociados.folhas.contracts.clients.MessageTemplateModel;
import br.com.contadoresassociados.folhas.contracts.clients.PersonTypeModel;
import br.com.contadoresassociados.folhas.contracts.clients.RecipientModel;
import br.com.contadoresassociados.folhas.contracts.clients.SignatureModeModel;
import br.com.contadoresassociados.folhas.server.db.Sql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Leitura e gravação JDBC das tabelas do catálogo (mesmo esquema do EF Core). */
final class CatalogStore {

    /** Cliente carregado com os metadados de autoria usados para reconstruir o agregado. */
    record ClientRow(ClientDetails details, UUID createdBy, UUID updatedBy, Instant archivedAt) {
    }

    record TemplateRow(MessageTemplateModel model, UUID updatedBy, Instant archivedAt) {
    }

    private static final String CLIENT_COLUMNS = "\"Id\", \"PersonType\", \"LegalNameOrFullName\", \"PreferredName\", "
            + "\"InternalCode\", \"PrimaryTaxIdNormalized\", \"IsActive\", \"DefaultSubjectTemplateId\", "
            + "\"DefaultBodyTemplateId\", \"Notes\", \"Version\", \"CreatedAtUtc\", \"UpdatedAtUtc\", \"CreatedBy\", "
            + "\"UpdatedBy\", \"ArchivedAtUtc\"";
    private static final String TEMPLATE_COLUMNS = "\"Id\", \"ClientId\", \"DocumentTypeId\", \"Name\", \"SubjectTemplate\", "
            + "\"BodyTemplate\", \"SignatureMode\", \"IsDefault\", \"IsActive\", \"Version\", \"UpdatedAtUtc\", \"UpdatedBy\", "
            + "\"ArchivedAtUtc\"";

    private CatalogStore() {
    }

    // ------------------------------------------------------------------ clientes

    static Optional<ClientRow> client(Connection c, UUID organizationId, UUID id, boolean forUpdate)
            throws SQLException {
        var rows = clients(c, organizationId, "\"Id\" = ?" + (forUpdate ? "" : " AND \"ArchivedAtUtc\" IS NULL"),
                List.of(id), forUpdate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    static List<ClientRow> clientsByIds(Connection c, UUID organizationId, Collection<UUID> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        return clients(c, organizationId, "\"Id\" = ANY (?) AND \"ArchivedAtUtc\" IS NULL", List.of(ids), false);
    }

    static List<ClientRow> allClients(Connection c, UUID organizationId) throws SQLException {
        return clients(c, organizationId, "\"ArchivedAtUtc\" IS NULL", List.of(), false);
    }

    private static List<ClientRow> clients(Connection c, UUID organizationId, String where, List<Object> args,
            boolean forUpdate) throws SQLException {
        var sql = "SELECT " + CLIENT_COLUMNS + " FROM clients WHERE \"OrganizationId\" = ? AND " + where
                + " ORDER BY \"LegalNameOrFullName\", \"Id\"" + (forUpdate ? " FOR UPDATE" : "");
        var heads = new LinkedHashMap<UUID, Object[]>();
        try (var st = c.prepareStatement(sql)) {
            st.setObject(1, organizationId);
            var i = 2;
            for (var arg : args) {
                if (arg instanceof Collection<?> col) {
                    st.setArray(i++, c.createArrayOf("uuid", col.toArray()));
                } else {
                    st.setObject(i++, arg);
                }
            }
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    heads.put(Sql.uuid(rs, "Id"), new Object[] {
                        Sql.fromPascal(PersonTypeModel.class, rs.getString("PersonType")),
                        rs.getString("LegalNameOrFullName"), rs.getString("PreferredName"), rs.getString("InternalCode"),
                        rs.getString("PrimaryTaxIdNormalized"), rs.getBoolean("IsActive"),
                        Sql.uuid(rs, "DefaultSubjectTemplateId"), Sql.uuid(rs, "DefaultBodyTemplateId"),
                        rs.getString("Notes"), rs.getLong("Version"), Sql.utc(rs, "CreatedAtUtc"),
                        Sql.utc(rs, "UpdatedAtUtc"), Sql.uuid(rs, "CreatedBy"), Sql.uuid(rs, "UpdatedBy"),
                        Sql.instant(rs, "ArchivedAtUtc")});
                }
            }
        }
        if (heads.isEmpty()) {
            return List.of();
        }
        var ids = heads.keySet().toArray();
        var identifiers = children(c, "SELECT \"Id\", \"ClientId\", \"Type\", \"ValueNormalized\", \"SemanticRole\", "
                + "\"Priority\", \"IsActive\", \"IsUniqueWithinOrganization\" FROM client_identifiers WHERE \"ClientId\" = ANY (?)",
                ids, rs -> new ClientIdentifierModel(Sql.uuid(rs, "Id"),
                        Sql.fromPascal(ClientIdentifierTypeModel.class, rs.getString("Type")), rs.getString("ValueNormalized"),
                        Sql.fromPascal(ClientIdentifierSemanticRoleModel.class, rs.getString("SemanticRole")),
                        rs.getInt("Priority"), rs.getBoolean("IsActive"), rs.getBoolean("IsUniqueWithinOrganization")));
        var establishments = children(c, "SELECT \"Id\", \"ClientId\", \"CnpjNormalized\", \"LegalName\", \"DisplayName\", "
                + "\"InternalCode\", \"IsHeadOffice\", \"IsActive\" FROM client_establishments WHERE \"ClientId\" = ANY (?)",
                ids, rs -> new EstablishmentModel(Sql.uuid(rs, "Id"), rs.getString("CnpjNormalized"),
                        rs.getString("LegalName"), rs.getString("DisplayName"), rs.getString("InternalCode"),
                        rs.getBoolean("IsHeadOffice"), rs.getBoolean("IsActive")));
        var recipients = children(c, "SELECT \"Id\", \"ClientId\", \"EstablishmentId\", \"DisplayName\", \"EmailNormalized\", "
                + "\"DeliveryRole\", \"DocumentTypeId\", \"IsPrimary\", \"IsActive\", \"ValidFrom\", \"ValidTo\" "
                + "FROM client_recipients WHERE \"ClientId\" = ANY (?)",
                ids, rs -> new RecipientModel(Sql.uuid(rs, "Id"), Sql.uuid(rs, "EstablishmentId"),
                        rs.getString("DisplayName"), rs.getString("EmailNormalized"),
                        Sql.fromPascal(DeliveryRoleModel.class, rs.getString("DeliveryRole")),
                        Sql.uuid(rs, "DocumentTypeId"), rs.getBoolean("IsPrimary"), rs.getBoolean("IsActive"),
                        Sql.date(rs, "ValidFrom"), Sql.date(rs, "ValidTo")));
        var partners = children(c, "SELECT \"Id\", \"ClientId\", \"FullName\", \"CpfNormalized\", \"Role\", \"IsActive\", "
                + "\"EmailNormalized\" FROM client_partners WHERE \"ClientId\" = ANY (?)",
                ids, rs -> new ClientPartnerModel(Sql.uuid(rs, "Id"), rs.getString("FullName"),
                        rs.getString("CpfNormalized"), Sql.fromPascal(ClientPartnerRoleModel.class, rs.getString("Role")),
                        rs.getBoolean("IsActive"), rs.getString("EmailNormalized")));
        var result = new ArrayList<ClientRow>(heads.size());
        for (var entry : heads.entrySet()) {
            var id = entry.getKey();
            var h = entry.getValue();
            var details = new ClientDetails(id, (PersonTypeModel) h[0], (String) h[1], (String) h[2], (String) h[3],
                    (String) h[4], (Boolean) h[5], (UUID) h[6], (UUID) h[7], (String) h[8], (Long) h[9],
                    (java.time.OffsetDateTime) h[10], (java.time.OffsetDateTime) h[11],
                    sorted(identifiers.get(id), Comparator.comparingInt(ClientIdentifierModel::priority)),
                    sorted(establishments.get(id), Comparator.comparing((EstablishmentModel e) -> !e.isHeadOffice())
                            .thenComparing(e -> e.displayName() == null ? "" : e.displayName())),
                    sorted(recipients.get(id), Comparator.comparing(RecipientModel::deliveryRole)
                            .thenComparing(r -> r.displayName() == null ? "" : r.displayName())),
                    sorted(partners.get(id), Comparator.comparing(ClientPartnerModel::role)
                            .thenComparing(p -> p.fullName() == null ? "" : p.fullName())));
            result.add(new ClientRow(details, (UUID) h[12], (UUID) h[13], (Instant) h[14]));
        }
        return result;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private static <T> Map<UUID, List<T>> children(Connection c, String sql, Object[] ids, Reader<T> reader)
            throws SQLException {
        var map = new HashMap<UUID, List<T>>();
        try (var st = c.prepareStatement(sql)) {
            st.setArray(1, c.createArrayOf("uuid", ids));
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    map.computeIfAbsent(Sql.uuid(rs, "ClientId"), k -> new ArrayList<>()).add(reader.read(rs));
                }
            }
        }
        return map;
    }

    private static <T> List<T> sorted(List<T> list, Comparator<T> comparator) {
        if (list == null) {
            return List.of();
        }
        var copy = new ArrayList<>(list);
        copy.sort(comparator);
        return copy;
    }

    /** Insere ou atualiza o cliente (versão conferida) e os filhos; filhos ausentes ficam inativos. */
    static void writeClient(Connection c, UUID organizationId, ClientDetails client, UUID createdBy, UUID updatedBy,
            long expectedStoredVersion, boolean insert) throws SQLException {
        if (insert) {
            try (var st = c.prepareStatement("INSERT INTO clients (\"Id\", \"OrganizationId\", \"PersonType\", "
                    + "\"LegalNameOrFullName\", \"PreferredName\", \"InternalCode\", \"PrimaryTaxIdNormalized\", \"IsActive\", "
                    + "\"DefaultSubjectTemplateId\", \"DefaultBodyTemplateId\", \"Notes\", \"CreatedBy\", \"UpdatedBy\", "
                    + "\"CreatedAtUtc\", \"UpdatedAtUtc\", \"Version\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                st.setObject(1, client.id());
                st.setObject(2, organizationId);
                st.setString(3, Sql.pascal(client.personType()));
                st.setString(4, client.legalNameOrFullName());
                st.setString(5, client.preferredName());
                st.setString(6, client.internalCode());
                st.setString(7, client.primaryTaxId());
                st.setBoolean(8, client.isActive());
                Sql.uuid(st, 9, client.defaultSubjectTemplateId());
                Sql.uuid(st, 10, client.defaultBodyTemplateId());
                st.setString(11, client.notes());
                st.setObject(12, createdBy);
                st.setObject(13, updatedBy);
                Sql.instant(st, 14, client.createdAtUtc().toInstant());
                Sql.instant(st, 15, client.updatedAtUtc().toInstant());
                st.setLong(16, client.version());
                st.executeUpdate();
            }
        } else {
            try (var st = c.prepareStatement("UPDATE clients SET \"LegalNameOrFullName\" = ?, \"PreferredName\" = ?, "
                    + "\"InternalCode\" = ?, \"PrimaryTaxIdNormalized\" = ?, \"IsActive\" = ?, \"DefaultSubjectTemplateId\" = ?, "
                    + "\"DefaultBodyTemplateId\" = ?, \"Notes\" = ?, \"UpdatedBy\" = ?, \"UpdatedAtUtc\" = ?, \"Version\" = ? "
                    + "WHERE \"Id\" = ? AND \"OrganizationId\" = ? AND \"Version\" = ? AND \"ArchivedAtUtc\" IS NULL")) {
                st.setString(1, client.legalNameOrFullName());
                st.setString(2, client.preferredName());
                st.setString(3, client.internalCode());
                st.setString(4, client.primaryTaxId());
                st.setBoolean(5, client.isActive());
                Sql.uuid(st, 6, client.defaultSubjectTemplateId());
                Sql.uuid(st, 7, client.defaultBodyTemplateId());
                st.setString(8, client.notes());
                st.setObject(9, updatedBy);
                Sql.instant(st, 10, client.updatedAtUtc().toInstant());
                st.setLong(11, client.version());
                st.setObject(12, client.id());
                st.setObject(13, organizationId);
                st.setLong(14, expectedStoredVersion);
                if (st.executeUpdate() != 1) {
                    throw new br.com.contadoresassociados.folhas.application.clients.CatalogException.Concurrency(
                            client.id(), expectedStoredVersion, -1);
                }
            }
        }
        for (var i : client.identifiers()) {
            child(c, "client_identifiers", i.id(), client.id(), organizationId,
                    new String[] {"Type", "ValueNormalized", "SemanticRole", "Priority", "IsActive", "IsUniqueWithinOrganization"},
                    new Object[] {Sql.pascal(i.type()), i.value(), Sql.pascal(i.semanticRole()), i.priority(), i.isActive(),
                        i.isUniqueWithinOrganization()});
        }
        for (var e : client.establishments()) {
            child(c, "client_establishments", e.id(), client.id(), organizationId,
                    new String[] {"CnpjNormalized", "CnpjRoot", "LegalName", "DisplayName", "InternalCode", "IsHeadOffice",
                        "IsActive"},
                    new Object[] {e.cnpj(), e.cnpj().substring(0, 8), e.legalName(), e.displayName(), e.internalCode(),
                        e.isHeadOffice(), e.isActive()});
        }
        for (var r : client.recipients()) {
            child(c, "client_recipients", r.id(), client.id(), organizationId,
                    new String[] {"EstablishmentId", "DisplayName", "EmailNormalized", "DeliveryRole", "DocumentTypeId",
                        "IsPrimary", "IsActive", "ValidFrom", "ValidTo"},
                    new Object[] {r.establishmentId(), r.displayName(), r.email(), Sql.pascal(r.deliveryRole()),
                        r.documentTypeId(), r.isPrimary(), r.isActive(), r.validFrom(), r.validTo()});
        }
        for (var p : client.partners()) {
            child(c, "client_partners", p.id(), client.id(), organizationId,
                    new String[] {"FullName", "CpfNormalized", "Role", "IsActive", "EmailNormalized"},
                    new Object[] {p.fullName(), p.cpf(), Sql.pascal(p.role()), p.isActive(), p.email()});
        }
        deactivateMissing(c, "client_identifiers", client.id(), client.identifiers().stream().map(ClientIdentifierModel::id).toList());
        deactivateMissing(c, "client_establishments", client.id(), client.establishments().stream().map(EstablishmentModel::id).toList());
        deactivateMissing(c, "client_recipients", client.id(), client.recipients().stream().map(RecipientModel::id).toList());
        deactivateMissing(c, "client_partners", client.id(), client.partners().stream().map(ClientPartnerModel::id).toList());
    }

    private static void child(Connection c, String table, UUID id, UUID clientId, UUID organizationId, String[] columns,
            Object[] values) throws SQLException {
        var cols = new StringBuilder("\"Id\", \"ClientId\", \"OrganizationId\"");
        var params = new StringBuilder("?, ?, ?");
        var updates = new StringBuilder();
        for (var col : columns) {
            cols.append(", \"").append(col).append('"');
            params.append(", ?");
            if (!updates.isEmpty()) {
                updates.append(", ");
            }
            updates.append('"').append(col).append("\" = EXCLUDED.\"").append(col).append('"');
        }
        var sql = "INSERT INTO " + table + " (" + cols + ") VALUES (" + params + ") ON CONFLICT (\"Id\") DO UPDATE SET "
                + updates + " WHERE " + table + ".\"ClientId\" = EXCLUDED.\"ClientId\" AND " + table
                + ".\"OrganizationId\" = EXCLUDED.\"OrganizationId\"";
        try (var st = c.prepareStatement(sql)) {
            st.setObject(1, id);
            st.setObject(2, clientId);
            st.setObject(3, organizationId);
            for (var i = 0; i < values.length; i++) {
                var v = values[i];
                var index = i + 4;
                switch (v) {
                    case null -> st.setObject(index, null);
                    case UUID u -> st.setObject(index, u);
                    case java.time.LocalDate d -> Sql.date(st, index, d);
                    default -> st.setObject(index, v);
                }
            }
            if (st.executeUpdate() != 1) {
                throw new br.com.contadoresassociados.folhas.application.clients.CatalogException("catalog.invalid",
                        "Um item do cadastro usa um identificador que pertence a outro registro.");
            }
        }
    }

    private static void deactivateMissing(Connection c, String table, UUID clientId, List<UUID> keep) throws SQLException {
        try (var st = c.prepareStatement("UPDATE " + table + " SET \"IsActive\" = false WHERE \"ClientId\" = ? AND "
                + "\"IsActive\" AND NOT (\"Id\" = ANY (?))")) {
            st.setObject(1, clientId);
            st.setArray(2, c.createArrayOf("uuid", keep.toArray()));
            st.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ modelos

    static List<TemplateRow> templates(Connection c, UUID organizationId, String where, List<Object> args,
            boolean forUpdate) throws SQLException {
        var sql = "SELECT " + TEMPLATE_COLUMNS + " FROM message_templates WHERE \"OrganizationId\" = ?"
                + (where.isEmpty() ? "" : " AND " + where) + " ORDER BY \"Name\", \"Id\"" + (forUpdate ? " FOR UPDATE" : "");
        var list = new ArrayList<TemplateRow>();
        try (var st = c.prepareStatement(sql)) {
            st.setObject(1, organizationId);
            var i = 2;
            for (var arg : args) {
                st.setObject(i++, arg);
            }
            try (var rs = st.executeQuery()) {
                while (rs.next()) {
                    list.add(new TemplateRow(new MessageTemplateModel(Sql.uuid(rs, "Id"), Sql.uuid(rs, "ClientId"),
                            Sql.uuid(rs, "DocumentTypeId"), rs.getString("Name"), rs.getString("SubjectTemplate"),
                            rs.getString("BodyTemplate"), Sql.fromPascal(SignatureModeModel.class, rs.getString("SignatureMode")),
                            rs.getBoolean("IsDefault"), rs.getBoolean("IsActive"), rs.getLong("Version"),
                            Sql.utc(rs, "UpdatedAtUtc")), Sql.uuid(rs, "UpdatedBy"), Sql.instant(rs, "ArchivedAtUtc")));
                }
            }
        }
        return list;
    }

    static Optional<TemplateRow> template(Connection c, UUID organizationId, UUID id, boolean forUpdate)
            throws SQLException {
        var list = templates(c, organizationId, "\"Id\" = ?" + (forUpdate ? "" : " AND \"ArchivedAtUtc\" IS NULL"),
                List.of(id), forUpdate);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    static void writeTemplate(Connection c, UUID organizationId, MessageTemplateModel t, UUID updatedBy,
            long expectedStoredVersion, boolean insert) throws SQLException {
        if (insert) {
            try (var st = c.prepareStatement("INSERT INTO message_templates (\"Id\", \"OrganizationId\", \"ClientId\", "
                    + "\"DocumentTypeId\", \"Name\", \"SubjectTemplate\", \"BodyTemplate\", \"SignatureMode\", \"IsDefault\", "
                    + "\"IsActive\", \"Version\", \"UpdatedBy\", \"UpdatedAtUtc\") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                st.setObject(1, t.id());
                st.setObject(2, organizationId);
                Sql.uuid(st, 3, t.clientId());
                Sql.uuid(st, 4, t.documentTypeId());
                st.setString(5, t.name());
                st.setString(6, t.subjectTemplate());
                st.setString(7, t.bodyTemplate());
                st.setString(8, Sql.pascal(t.signatureMode()));
                st.setBoolean(9, t.isDefault());
                st.setBoolean(10, t.isActive());
                st.setLong(11, t.version());
                st.setObject(12, updatedBy);
                Sql.instant(st, 13, t.updatedAtUtc().toInstant());
                st.executeUpdate();
            }
            return;
        }
        try (var st = c.prepareStatement("UPDATE message_templates SET \"ClientId\" = ?, \"DocumentTypeId\" = ?, \"Name\" = ?, "
                + "\"SubjectTemplate\" = ?, \"BodyTemplate\" = ?, \"SignatureMode\" = ?, \"IsDefault\" = ?, \"IsActive\" = ?, "
                + "\"Version\" = ?, \"UpdatedBy\" = ?, \"UpdatedAtUtc\" = ? WHERE \"Id\" = ? AND \"OrganizationId\" = ? AND "
                + "\"Version\" = ? AND \"ArchivedAtUtc\" IS NULL")) {
            Sql.uuid(st, 1, t.clientId());
            Sql.uuid(st, 2, t.documentTypeId());
            st.setString(3, t.name());
            st.setString(4, t.subjectTemplate());
            st.setString(5, t.bodyTemplate());
            st.setString(6, Sql.pascal(t.signatureMode()));
            st.setBoolean(7, t.isDefault());
            st.setBoolean(8, t.isActive());
            st.setLong(9, t.version());
            st.setObject(10, updatedBy);
            Sql.instant(st, 11, t.updatedAtUtc().toInstant());
            st.setObject(12, t.id());
            st.setObject(13, organizationId);
            st.setLong(14, expectedStoredVersion);
            if (st.executeUpdate() != 1) {
                throw new br.com.contadoresassociados.folhas.application.clients.CatalogException.Concurrency(t.id(),
                        expectedStoredVersion, -1);
            }
        }
    }
}
