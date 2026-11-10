package java.util;

/**
 * An instant, in milliseconds since 1970-01-01T00:00:00Z. LazyVM has no time-zone data, so
 * toString shows UTC or the fixed GMT offset user.timezone names (docs/design/time.md, Z1). Only
 * the non-deprecated members exist; the calendar-field ones wait for java.text, and the java.time
 * conversions are not provided. Serialization comes with java.io object streams; until then the
 * class only declares that it is Serializable.
 */
public class Date implements java.io.Serializable, Cloneable, Comparable<Date> {
    private static final long serialVersionUID = 7523967970034938905L;

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

    /** "EEE MMM dd HH:mm:ss zzz yyyy" in the default zone, with the year of its era (1 BC is 1). */
    public String toString() {
        return TimeFormat.dateString(millis);
    }
}
