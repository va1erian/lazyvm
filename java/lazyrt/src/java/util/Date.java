package java.util;

import lazy.internal.Calendars;

/**
 * An instant, in milliseconds since 1970-01-01T00:00:00Z. LazyVM has no time-zone data yet, so
 * toString always shows UTC (docs/design/util-text.md, P11). Only the non-deprecated members
 * exist; the calendar-field ones wait for java.text and time zones, and the java.time
 * conversions for java.time. Serialization comes with java.io object streams; until then the
 * class only declares that it is Serializable.
 */
public class Date implements java.io.Serializable, Cloneable, Comparable<Date> {
    private static final long serialVersionUID = 7523967970034938905L;
    private static final long DAY = 86400000L;
    /** The first instant of the Gregorian calendar, 1582-10-15T00:00Z; earlier ones are Julian. */
    private static final long GREGORIAN_START = -12219292800000L;
    private static final String DAYS = "SunMonTueWedThuFriSat";
    private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

    private long millis;

    public Date() {
        this(System.currentTimeMillis());
    }

    public Date(long date) {
        millis = date;
    }

    public long getTime() {
        return millis;
    }

    public void setTime(long time) {
        millis = time;
    }

    /** Ordering and toString use the instants themselves, not an overridden getTime(). */
    public boolean before(Date when) {
        return millis < when.millis;
    }

    public boolean after(Date when) {
        return millis > when.millis;
    }

    /** Equality and the hash ask getTime(), as the Javadoc defines them. */
    public boolean equals(Object obj) {
        return obj instanceof Date && getTime() == ((Date) obj).getTime();
    }

    public int compareTo(Date anotherDate) {
        return Long.compare(millis, anotherDate.millis);
    }

    public int hashCode() {
        long t = getTime();
        return (int) (t ^ (t >>> 32));
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
        long t = millis;
        long days = Math.floorDiv(t, DAY);
        int ms = (int) Math.floorMod(t, DAY);
        long[] ymd = t < GREGORIAN_START ? Calendars.julian(days) : Calendars.gregorian(days);
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
}
