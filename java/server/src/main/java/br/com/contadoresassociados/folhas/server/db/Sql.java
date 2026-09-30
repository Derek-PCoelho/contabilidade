package br.com.contadoresassociados.folhas.server.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/** Conversões JDBC ↔ tipos do domínio (enums gravados em PascalCase, como no EF Core). */
public final class Sql {

    private Sql() {
    }

    public static void uuid(PreparedStatement st, int index, UUID value) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.OTHER);
        } else {
            st.setObject(index, value);
        }
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    public static void instant(PreparedStatement st, int index, Instant value) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            st.setObject(index, value.atOffset(ZoneOffset.UTC));
        }
    }

    public static OffsetDateTime utc(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static void date(PreparedStatement st, int index, LocalDate value) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.DATE);
        } else {
            st.setObject(index, value);
        }
    }

    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    public static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** {@code LEGAL_ENTITY} → {@code LegalEntity}. */
    public static String pascal(Enum<?> value) {
        if (value == null) {
            return null;
        }
        var sb = new StringBuilder();
        for (var part : value.name().split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    /** {@code LegalEntity} → {@code LEGAL_ENTITY}. */
    public static <E extends Enum<E>> E fromPascal(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        for (var constant : type.getEnumConstants()) {
            if (pascal(constant).equalsIgnoreCase(value) || constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("Valor desconhecido para " + type.getSimpleName() + ": " + value);
    }
}
