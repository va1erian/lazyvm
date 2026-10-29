// VMARGS: -Duser.timezone=UTC
package util;

import java.util.*;

/**
 * java.util.Date's non-deprecated members. Date.toString uses the default time zone, so both VMs
 * run this test with -Duser.timezone=UTC (LazyVM always shows UTC; util-text.md section 4).
 * Covers the Julian/Gregorian switch, years before the common era, the long extremes, hashCode,
 * and equals, compareTo, before and after through an overridden getTime().
 */
public class Dates {
    static final long DAY = 86400000L;
    /** 1582-10-15T00:00Z, the first Gregorian day. */
    static final long SWITCH = -12219292800000L;
    /** 0001-01-03T00:00Z in the proleptic Gregorian calendar, which is 0001-01-01 Julian. */
    static final long YEAR_ONE = -62135596800000L - 2 * DAY;

    /** A Date whose getTime() is shifted, to see which methods ask getTime(). */
    static class Shifted extends Date {
        final long shift;
        Shifted(long t, long shift) { super(t); this.shift = shift; }
        public long getTime() { return super.getTime() + shift; }
    }

    static long seed = 0xda7e5L;

    static long rnd() {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return seed;
    }

    public static void main(String[] args) {
        long[] fixed = {0, -1, 1, DAY - 1, SWITCH, SWITCH - 1, SWITCH - 10 * DAY, YEAR_ONE, YEAR_ONE - 1, YEAR_ONE - 366 * DAY,
            YEAR_ONE - 367 * DAY, -62198755200000L, 253402300799999L, 253402300800000L, 951782400000L, 4107542400000L,
            Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE - 1, Long.MIN_VALUE + 1, -100000000000000L, 1000000000000000L};
        for (long t : fixed) {
            Date d = new Date(t);
            System.out.println(t + " " + d + " hash " + d.hashCode());
        }
        // Many instants: the strings and hashes are pinned through one checksum.
        long h = 0;
        long[] scales = {1L << 62, 1L << 45, 1L << 38, 400L * DAY};
        long[] centers = {0, SWITCH, YEAR_ONE, 253402300800000L};
        for (int i = 0; i < 4000; i++) {
            long t = centers[(int) ((rnd() >>> 33) % 4)] + rnd() % scales[(int) ((rnd() >>> 33) % 4)];
            Date d = new Date(t);
            h = h * 31 + d.toString().hashCode() + d.hashCode();
            if (i % 500 == 0) System.out.println("sample " + t + " " + d);
        }
        System.out.println("checksum " + h);
        // The comparisons, equality and hash ask getTime(), which a subclass can change.
        Date a = new Date(1000);
        Date b = new Shifted(0, 1000);
        Date c = new Shifted(1000, 1);
        System.out.println("equals " + a.equals(b) + " " + b.equals(a) + " " + a.equals(c) + " " + b.equals(b) + " " + a.equals(null) + " " + a.equals("x"));
        System.out.println("hashCode " + a.hashCode() + " " + b.hashCode() + " " + c.hashCode());
        System.out.println("compareTo " + a.compareTo(b) + " " + a.compareTo(c) + " " + c.compareTo(a) + " " + a.compareTo(a));
        System.out.println("before after " + a.before(c) + " " + c.after(a) + " " + a.before(b) + " " + b.after(a) + " " + c.before(a));
        System.out.println("shifted toString " + b + " | " + c);
        System.out.println("extremes " + new Date(Long.MIN_VALUE).compareTo(new Date(Long.MAX_VALUE)) + " " + new Date(Long.MAX_VALUE).before(new Date(Long.MIN_VALUE)));
        // setTime, getTime and clone.
        Date e = new Date(5);
        Date f = (Date) e.clone();
        e.setTime(-5);
        System.out.println("clone " + (f != e) + " " + f.getTime() + " " + e.getTime() + " " + f.getClass().getSimpleName() + " " + ((Date) b.clone()).getTime());
        System.out.println("nulls " + ex(() -> a.compareTo(null)) + " " + ex(() -> a.before(null)) + " " + ex(() -> a.after(null)));
        Date now = new Date();
        long wall = System.currentTimeMillis();
        System.out.println("now within a minute " + (Math.abs(wall - now.getTime()) < 60000));
        System.out.println("done");
    }

    interface Op { Object run(); }

    static String ex(Op o) {
        try {
            return String.valueOf(o.run());
        } catch (RuntimeException e) {
            return e.getClass().getName();
        }
    }
}
