package java.nio.file.attribute;

import java.util.concurrent.TimeUnit;

/**
 * A file time stamp: the value and unit it was made from, for conversions,
 * and whole seconds from the epoch with nanoseconds within the second, so
 * that equal instants in different units compare equal (Spec NF6). The
 * java.time conversions come with S11.
 */
public final class FileTime implements Comparable<FileTime> {
    private static final long NANOS_PER_SECOND = 1000000000L;

    private final long value;
    private final TimeUnit unit;
    private final long seconds;
    private final int nanos;

    private FileTime(long value, TimeUnit unit, long seconds, int nanos) {
        this.value = value;
        this.unit = unit;
        this.seconds = seconds;
        this.nanos = nanos;
    }

    public static FileTime from(long value, TimeUnit unit) {
        if (unit == null) {
            throw new NullPointerException();
        }
        long perUnit = unit.toNanos(1);
        if (perUnit < NANOS_PER_SECOND) {
            long perSecond = NANOS_PER_SECOND / perUnit;
            return new FileTime(value, unit, Math.floorDiv(value, perSecond),
                    (int) (Math.floorMod(value, perSecond) * perUnit));
        }
        return new FileTime(value, unit, unit.toSeconds(value), 0);
    }

    public static FileTime fromMillis(long value) {
        return from(value, TimeUnit.MILLISECONDS);
    }

    /** Observed: converted from the value and unit it was made with, as TimeUnit converts. */
    public long to(TimeUnit target) {
        return target.convert(value, unit);
    }

    public long toMillis() {
        return to(TimeUnit.MILLISECONDS);
    }

    public int compareTo(FileTime other) {
        int c = Long.compare(seconds, other.seconds);
        return c != 0 ? c : Integer.compare(nanos, other.nanos);
    }

    public boolean equals(Object obj) {
        return obj instanceof FileTime && compareTo((FileTime) obj) == 0;
    }

    /** Observed: the hash of the seconds plus 51 times the nanoseconds. */
    public int hashCode() {
        return (int) (seconds ^ (seconds >>> 32)) + 51 * nanos;
    }

    // Spec NF6: ISO-8601 in UTC, seconds always, a fraction without trailing zeros if any.
    public String toString() {
        long days = Math.floorDiv(seconds, 86400L);
        int secondOfDay = (int) Math.floorMod(seconds, 86400L);
        long[] ymd = lazy.internal.Calendars.gregorian(days);
        StringBuilder sb = new StringBuilder();
        long year = ymd[0];
        // Observed: year 0 and before are shown as years before the common era (0 is -0001).
        if (year <= 0) {
            sb.append('-');
            year = 1 - year;
        }
        pad(sb, year, 4);
        sb.append('-');
        pad(sb, ymd[1], 2);
        sb.append('-');
        pad(sb, ymd[2], 2);
        sb.append('T');
        pad(sb, secondOfDay / 3600, 2);
        sb.append(':');
        pad(sb, secondOfDay / 60 % 60, 2);
        sb.append(':');
        pad(sb, secondOfDay % 60, 2);
        if (nanos != 0) {
            String f = Integer.toString(nanos + 1000000000).substring(1);
            int end = f.length();
            while (f.charAt(end - 1) == '0') {
                end--;
            }
            sb.append('.').append(f, 0, end);
        }
        return sb.append('Z').toString();
    }

    private static void pad(StringBuilder sb, long v, int width) {
        String s = Long.toString(v);
        for (int i = s.length(); i < width; i++) {
            sb.append('0');
        }
        sb.append(s);
    }
}
