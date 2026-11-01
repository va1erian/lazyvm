package lazy.internal;

import java.time.DateTimeException;

/** Small pieces shared by java.time, its formatter and the `%t` conversions. */
public final class TimeChecks {
    /** Days before the first of each month in a year that is not a leap year. */
    private static final int[] DAYS_BEFORE = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};

    private TimeChecks() {
    }

    /** The one wording of "this field value is out of range". */
    public static DateTimeException range(String field, String valid, long value) {
        return new DateTimeException("Invalid value for " + field + " (valid values " + valid + "): " + value);
    }

    public static void check(String field, long lo, long hi, long value) {
        if (value < lo || value > hi) {
            throw range(field, lo + " - " + hi, value);
        }
    }

    /** Days of the year before day 1 of `month` (1 to 12). */
    public static int daysBefore(int month, boolean leap) {
        return DAYS_BEFORE[month - 1] + (leap && month > 2 ? 1 : 0);
    }

    /** Appends `v` with at least `width` digits; a negative value gets its `-` first. */
    public static StringBuilder pad(StringBuilder b, long v, int width) {
        String digits = Long.toString(v);
        if (v < 0) {
            b.append('-');
            digits = digits.substring(1);
        }
        for (int i = digits.length(); i < width; i++) {
            b.append('0');
        }
        return b.append(digits);
    }
}
