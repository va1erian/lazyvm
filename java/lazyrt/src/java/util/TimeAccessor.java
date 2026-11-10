package java.util;

import java.time.DateTimeException;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;

/**
 * A TemporalAccessor read by the `%t` conversions. A field it cannot give, or gives outside the
 * range of that field, fails the conversion.
 */
final class TimeAccessor implements TimeFormat.Source {
    /** In the order of the field numbers in TimeFormat. */
    private static final ChronoField[] FIELDS = {ChronoField.MILLI_OF_SECOND, ChronoField.SECOND_OF_MINUTE,
        ChronoField.MINUTE_OF_HOUR, ChronoField.HOUR_OF_DAY, ChronoField.CLOCK_HOUR_OF_AMPM,
        ChronoField.AMPM_OF_DAY, ChronoField.DAY_OF_WEEK, ChronoField.DAY_OF_MONTH, ChronoField.DAY_OF_YEAR,
        ChronoField.MONTH_OF_YEAR, ChronoField.YEAR_OF_ERA};
    /** The smallest and largest value of each field above. */
    private static final long[] LOW = {0, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1};
    private static final long[] HIGH = {999, 59, 59, 23, 12, 1, 7, 31, 366, 12, 1000000000};

    private final TemporalAccessor temporal;

    TimeAccessor(TemporalAccessor temporal) {
        this.temporal = temporal;
    }

    public long get(int field, char conversion) {
        ChronoField f = FIELDS[field];
        if (!temporal.isSupported(f)) {
            throw fail(conversion);
        }
        long v;
        try {
            v = temporal.getLong(f);
        } catch (DateTimeException e) {
            throw fail(conversion);
        }
        if (v < LOW[field] || v > HIGH[field]) {
            throw fail(conversion);
        }
        return v;
    }

    /** A date-time without a zone has no instant. */
    public long instant(char conversion) {
        throw fail(conversion);
    }

    private IllegalFormatConversionException fail(char conversion) {
        return new IllegalFormatConversionException(conversion, temporal.getClass());
    }
}
