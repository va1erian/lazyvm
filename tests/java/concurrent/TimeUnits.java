package concurrent;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** TimeUnit: the conversion table, saturation, truncation, enum shape, the blocking helpers. */
public class TimeUnits {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static void table(long v) {
        for (TimeUnit from : TimeUnit.values()) {
            StringBuilder b = new StringBuilder(from + "(" + v + "):");
            b.append(' ').append(from.toNanos(v)).append(' ').append(from.toMicros(v)).append(' ').append(from.toMillis(v))
                .append(' ').append(from.toSeconds(v)).append(' ').append(from.toMinutes(v)).append(' ').append(from.toHours(v))
                .append(' ').append(from.toDays(v));
            for (TimeUnit to : TimeUnit.values()) b.append(' ').append(to.convert(v, from));
            System.out.println(b);
        }
    }

    public static void main(String[] args) throws Exception {
        show("values", () -> Arrays.toString(TimeUnit.values()));
        show("valueOf", () -> TimeUnit.valueOf("DAYS"));
        show("valueOf bad", () -> TimeUnit.valueOf("WEEKS"));
        show("ordinal", TimeUnit.HOURS::ordinal);
        show("compare", () -> TimeUnit.SECONDS.compareTo(TimeUnit.MINUTES) < 0);
        show("declaring", () -> TimeUnit.DAYS.getDeclaringClass().getName());
        for (long v : new long[] {0, 1, -1, 999, -999, 1001, 86400, 90061001L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE / 1000, Long.MAX_VALUE / 1000 + 1}) table(v);

        show("sleep zero", () -> { TimeUnit.SECONDS.sleep(0); return "ok"; });
        show("sleep negative", () -> { TimeUnit.SECONDS.sleep(-1); return "ok"; });
        show("sleep small", () -> {
            long t0 = System.nanoTime();
            TimeUnit.MILLISECONDS.sleep(5);
            return System.nanoTime() - t0 >= 1_000_000L;
        });
        show("sleep nanos", () -> { TimeUnit.NANOSECONDS.sleep(10); return "ok"; });
        Thread.currentThread().interrupt();
        show("sleep flagged", () -> { TimeUnit.MILLISECONDS.sleep(1); return "no"; });
        Thread.currentThread().interrupt();
        show("sleep zero flagged", () -> { TimeUnit.MILLISECONDS.sleep(0); return "ok"; });
        show("flag after zero sleep", Thread::interrupted);

        Object mon = new Object();
        show("timedWait no monitor", () -> { TimeUnit.MILLISECONDS.timedWait(mon, 1); return "no"; });
        show("timedWait no monitor, zero", () -> { TimeUnit.MILLISECONDS.timedWait(mon, 0); return "skipped"; });
        show("timedWait null", () -> { TimeUnit.MILLISECONDS.timedWait(null, 1); return "no"; });
        show("timedWait null zero", () -> { TimeUnit.MILLISECONDS.timedWait(null, 0); return "skipped"; });
        show("timedWait", () -> {
            synchronized (mon) { TimeUnit.MILLISECONDS.timedWait(mon, 2); }
            return "ok";
        });
        show("timedWait nanos", () -> {
            synchronized (mon) { TimeUnit.NANOSECONDS.timedWait(mon, 10); }
            return "ok";
        });
        show("timedWait negative", () -> {
            synchronized (mon) { TimeUnit.SECONDS.timedWait(mon, -1); }
            return "ok";
        });
        Thread t = new Thread(() -> { });
        t.start();
        show("timedJoin", () -> { TimeUnit.SECONDS.timedJoin(t, 30); return t.isAlive(); });
        show("timedJoin zero", () -> { TimeUnit.SECONDS.timedJoin(t, 0); return "ok"; });
        show("timedJoin negative", () -> { TimeUnit.SECONDS.timedJoin(t, -1); return "ok"; });
        show("timedJoin null", () -> { TimeUnit.SECONDS.timedJoin(null, 1); return "no"; });
        show("timedJoin null zero", () -> { TimeUnit.SECONDS.timedJoin(null, 0); return "skipped"; });
        Thread sleeper = new Thread(() -> {
            try { Thread.sleep(30_000); } catch (InterruptedException e) { }
        });
        sleeper.setDaemon(true);
        sleeper.start();
        show("timedJoin expires", () -> { TimeUnit.MILLISECONDS.timedJoin(sleeper, 2); return sleeper.isAlive(); });
        sleeper.interrupt();
        sleeper.join();
    }
}
