package java.util;

/**
 * An instant, in milliseconds since 1970-01-01T00:00:00Z. LazyVM has no time-zone data yet, so
 * toString always shows UTC (docs/design/util-text.md, P11). Only the non-deprecated members
 * exist; the calendar-field ones wait for java.text and time zones, and the java.time
 * conversions for java.time.
 */
public class Date implements java.io.Serializable, Cloneable, Comparable<Date> {
    private static final long serialVersionUID = 7523967970034938905L;
    private static final long DAY = 86400000L;
    /** The first instant of the Gregorian calendar, 1582-10-15T00:00Z; earlier ones are Julian. */
    private static final long GREGORIAN_START = -12219292800000L;
    /** The Julian day number of 1970-01-01. */
    private static final long EPOCH_JDN = 2440588L;
    private static final String DAYS = "SunMonTueWedThuFriSat";
    private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

    private transient long fastTime;

    public Date() {
        this(System.currentTimeMillis());
    }

    public Date(long date) {
        fastTime = date;
    }

    public long getTime() {
        return fastTime;
    }

    public void setTime(long time) {
        fastTime = time;
    }

    public boolean before(Date when) {
        return fastTime < when.fastTime;
    }

    public boolean after(Date when) {
        return fastTime > when.fastTime;
    }

    public boolean equals(Object obj) {
        return obj instanceof Date && ((Date) obj).fastTime == fastTime;
    }

    public int compareTo(Date anotherDate) {
        return Long.compare(fastTime, anotherDate.fastTime);
    }

    public int hashCode() {
        return (int) (fastTime ^ (fastTime >>> 32));
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /** "EEE MMM dd HH:mm:ss zzz yyyy", with the year of its era (1 BC is 1). */
    public String toString() {
        long days = Math.floorDiv(fastTime, DAY);
        int ms = (int) Math.floorMod(fastTime, DAY);
        long[] ymd = civil(days + EPOCH_JDN, fastTime < GREGORIAN_START);
        int dow = (int) Math.floorMod(days + 4, 7L); // 1970-01-01 was a Thursday
        StringBuilder b = new StringBuilder(28);
        b.append(DAYS, 3 * dow, 3 * dow + 3).append(' ');
        int m = (int) ymd[1] - 1;
        b.append(MONTHS, 3 * m, 3 * m + 3).append(' ');
        two(b, (int) ymd[2]).append(' ');
        two(b, ms / 3600000).append(':');
        two(b, ms / 60000 % 60).append(':');
        two(b, ms / 1000 % 60).append(" UTC ");
        long year = ymd[0];
        return b.append(year > 0 ? year : 1 - year).toString();
    }

    private static StringBuilder two(StringBuilder b, int v) {
        return b.append((char) ('0' + v / 10)).append((char) ('0' + v % 10));
    }

    /**
     * Year (astronomical: 0 is 1 BC), month and day of a Julian day number, in the Julian or the
     * proleptic Gregorian calendar (the usual integer conversion, with floor division).
     */
    private static long[] civil(long jdn, boolean julian) {
        long c;
        long century = 0;
        if (julian) {
            c = jdn + 32082;
        } else {
            long a = jdn + 32044;
            century = Math.floorDiv(4 * a + 3, 146097L);
            c = a - Math.floorDiv(146097 * century, 4L);
        }
        long d = Math.floorDiv(4 * c + 3, 1461L);
        long e = c - Math.floorDiv(1461 * d, 4L);
        long m = Math.floorDiv(5 * e + 2, 153L);
        long day = e - Math.floorDiv(153 * m + 2, 5L) + 1;
        long month = m + 3 - 12 * (m / 10);
        long year = 100 * century + d - 4800 + m / 10;
        return new long[] {year, month, day};
    }
}
