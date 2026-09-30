package br.com.contadoresassociados.folhas.infrastructure.persistence.local;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

/**
 * Codificação idêntica à do EF Core/Microsoft.Data.Sqlite usada pela versão .NET:
 * GUID como TEXT maiúsculo; {@code DateTimeOffset} com {@code HasConversion<long>()}
 * ({@code DateTimeOffsetToBinaryConverter}: {@code ((ticks / 1000) << 11) | (offsetMinutos & 0x7FF)});
 * enum de coluna auxiliar como nome PascalCase ({@code Enum.ToString()}).
 */
public final class SqliteCodec {

    /** Ticks .NET em 1970-01-01T00:00 (0001-01-01 = 0). */
    private static final long EPOCH_TICKS = 621_355_968_000_000_000L;

    private SqliteCodec() {
    }

    public static String guid(UUID value) {
        return value == null ? null : value.toString().toUpperCase(Locale.ROOT);
    }

    public static UUID guid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    public static long dateTimeOffset(OffsetDateTime value) {
        var local = value.toLocalDateTime();
        long seconds = local.toEpochSecond(ZoneOffset.UTC);
        long ticks = EPOCH_TICKS + seconds * 10_000_000L + local.getNano() / 100;
        long offsetMinutes = value.getOffset().getTotalSeconds() / 60;
        return ((ticks / 1000) << 11) | (offsetMinutes & 0x07FF);
    }

    public static OffsetDateTime dateTimeOffset(long value) {
        long ticks = (value >> 11) * 1000;
        int offsetMinutes = (int) ((value << 53) >> 53);
        long sinceEpoch = ticks - EPOCH_TICKS;
        long seconds = Math.floorDiv(sinceEpoch, 10_000_000L);
        int nanos = (int) Math.floorMod(sinceEpoch, 10_000_000L) * 100;
        var local = LocalDateTime.ofEpochSecond(seconds, nanos, ZoneOffset.UTC);
        return OffsetDateTime.of(local, ZoneOffset.ofTotalSeconds(offsetMinutes * 60));
    }

    /** Precisão efetiva do conversor do EF: 100 µs. */
    public static OffsetDateTime truncate(OffsetDateTime value) {
        return value.truncatedTo(ChronoUnit.MICROS).withNano(value.getNano() / 100_000 * 100_000);
    }

    /** {@code READY_FOR_REVIEW} → {@code ReadyForReview}. */
    public static String pascal(Enum<?> value) {
        if (value == null) {
            return "";
        }
        var sb = new StringBuilder();
        for (var part : value.name().split("_")) {
            if (!part.isEmpty()) {
                sb.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
