package lazy.internal;

/**
 * Calendar dates of day counts, shared by java.util.Date and java.nio.file.attribute.FileTime.
 * Years are astronomical (0 is 1 BC). The conversions are the usual integer ones from a Julian
 * day number, with floor division so that every long day count works.
 */
public final class Calendars {
    /** The Julian day number of 1970-01-01. */
    private static final long EPOCH_JDN = 2440588L;

    private Calendars() {
    }

    /** {year, month, day} of a day count from 1970-01-01 in the proleptic Gregorian calendar. */
    public static long[] gregorian(long epochDay) {
        long a = epochDay + EPOCH_JDN + 32044;
        long century = Math.floorDiv(4 * a + 3, 146097L);
        return finish(a - Math.floorDiv(146097 * century, 4L), 100 * century);
    }

    /** {year, month, day} of a day count from 1970-01-01 in the Julian calendar. */
    public static long[] julian(long epochDay) {
        return finish(epochDay + EPOCH_JDN + 32082, 0);
    }

    /** The common tail of both conversions: `c` days into the cycle, `years` before it. */
    private static long[] finish(long c, long years) {
        long d = Math.floorDiv(4 * c + 3, 1461L);
        long e = c - Math.floorDiv(1461 * d, 4L);
        long m = Math.floorDiv(5 * e + 2, 153L);
        long day = e - Math.floorDiv(153 * m + 2, 5L) + 1;
        long month = m + 3 - 12 * (m / 10);
        long year = years + d - 4800 + m / 10;
        return new long[] {year, month, day};
    }
}
