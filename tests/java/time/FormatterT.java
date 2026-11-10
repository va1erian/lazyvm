// VMARGS: -Duser.timezone=UTC
package time;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
/** The %t and %T conversions of java.util.Formatter (docs/design/time.md, section 15). */
public class FormatterT {
    /** An accessor whose isSupported and getLong answers are chosen by the test. */
    static class Acc implements TemporalAccessor {
        final boolean supported;
        final long value;
        final boolean throwing;
        Acc(boolean supported, long value, boolean throwing) { this.supported = supported; this.value = value; this.throwing = throwing; }
        public boolean isSupported(TemporalField f) { return supported; }
        public long getLong(TemporalField f) {
            if (throwing) throw new DateTimeException("custom");
            return value;
        }
    }
    static void f(String fmt, Object... a) {
        try { System.out.println("[" + fmt + "] " + String.format(Locale.US, fmt, a)); } catch (Throwable e) { System.out.println("[" + fmt + "] " + e.getClass().getSimpleName() + ":" + e.getMessage()); }
    }
    public static void main(String[] x) {
        // the whole matrix: each suffix over each kind of argument (X1, X3)
        long ms = 1577934245123L; // 2020-01-02T03:04:05.123Z, a Thursday
        Object[] args = {ms, Long.valueOf(ms), new Date(ms), LocalDateTime.of(2020, 1, 2, 15, 4, 5, 123456789), null, "str", 5};
        String suffixes = "HIklMSLNpzZsQBbhAaCYymdejRTrDFc";
        for (char c : suffixes.toCharArray()) {
            StringBuilder sb = new StringBuilder();
            for (Object a : args) {
                try {
                    sb.append('|').append(String.format(Locale.US, "%t" + c, a));
                } catch (Throwable e) {
                    sb.append('|').append(e.getClass().getSimpleName()).append(':').append(e.getMessage());
                }
            }
            System.out.println("t" + c + " " + sb);
        }
        // the rest, one call each (X2 to X5)
        f("%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp", ms);
        f("%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp", new Date(0));
        f("%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp", LocalDateTime.of(2020, 12, 25, 0, 5, 9));
        f("%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp", LocalDateTime.of(2020, 12, 5, 12, 5, 9));
        f("%tY-%<tm-%<td %<tH:%<tM:%<tS.%<tL", ms);
        f("%Tp %TP", ms, ms);
        f("%TB|%Tb|%Th|%TA|%Ta|%TZ|%Tc|%TD|%TF|%Tr|%TR|%TT|%TH|%TY|%Tj|%Te|%Tz", ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms, ms);
        f("%-12tB|", ms); f("%12tB|", ms); f("%012tY|", ms); f("%3tY|", ms); f("%1tB|", ms); f("%12TB|", ms); f("%-12Tc|", ms); f("%30tc|", ms); f("%5te|", ms);
        f("%.2tH", ms); f("%#tH", ms); f("%+tH", ms); f("% tH", ms); f("%,tH", ms); f("%(tH", ms); f("%0tH", ms); f("%-tH", ms); f("%-tH|", ms); f("%<tH", ms);
        f("%t"); f("%t", ms); f("%tq", ms); f("%tx", ms); f("%t%", ms); f("%tH%", ms); f("%T", ms); f("%Tq", ms); f("%t1", ms); f("%t H", ms); f("%-t", ms);
        f("%tH %<tM", ms, 5); f("%tH %tM", ms); f("%2$tH %1$tM", ms, 0L); f("%tH %tH", ms, 0L); f("%0$tH", ms);
        f("%tH|%tH", (Object) null, (Object) null); f("%10tH|", (Object) null); f("%-10tH|", (Object) null); f("%TH|", (Object) null); f("%tc", (Object) null);
        f("%tY %<tm %<td %<tH %<tj %<ts %<tQ", 0L);
        f("%tY %<tm %<td %<tH %<tj %<ts %<tQ", -1L);
        f("%tY %<tm %<td %<tH %<tM %<tS %<tL %<tN %<tj %<ts %<tQ", Long.MIN_VALUE);
        f("%tY %<tm %<td %<tH %<tM %<tS %<tL %<tN %<tj %<ts %<tQ", Long.MAX_VALUE);
        f("%tY %<tm %<td %<tA %<ta %<tj %<tC %<ty", -62167219200000L);
        f("%tY %<tm %<td %<tA %<ta %<tj %<tC %<ty", -62167219201000L);
        f("%tY %<tm %<td %<tA %<ta %<tj %<tC %<ty", -62198755200000L);
        f("%tY %<tm %<td %<tC %<ty", -377705116800000L);
        f("%tY %<tm %<td %<tC %<ty", 253402300800000L);
        f("%tF %<tT %<tD %<tr %<tR", 253402300800000L);
        f("%tc", LocalDateTime.of(2020, 9, 1, 1, 2, 3));
        f("%tH", true); f("%tH", 5L); f("%tH", (short) 5); f("%tH", 1.5); f("%tH", 'c'); f("%tH", new StringBuilder());
        f("%tH", new java.util.concurrent.atomic.AtomicLong(5));
        // accessors that disagree with themselves (X9): a field it cannot give, or a getLong that throws
        f("%tH", new Acc(false, 5, false)); f("%tH", new Acc(true, 5, true)); f("%tY", new Acc(true, 5, true)); f("%tH", new Acc(true, 5, false));
        f("%tH:%<tM", new Acc(true, 7, false));
        f("%tB", new Acc(true, 99, false)); f("%tA", new Acc(true, 99, false)); f("%tp", new Acc(true, 99, false));
        // a value outside the range of its field fails like a field the accessor cannot give
        for (String sfx : new String[] {"H", "I", "k", "l", "M", "S", "L", "N", "p", "B", "b", "h", "A", "a", "C", "Y", "y", "m", "d", "e", "j"}) {
            StringBuilder sb = new StringBuilder();
            for (long v : new long[] {-1, 0, 1, 12, 13, 24, 32, 60, 367, 1000, 2000000000L}) {
                try { sb.append(v).append('=').append(String.format(Locale.US, "%t" + sfx, new Acc(true, v, false))); } catch (Throwable e) { sb.append(v).append('!'); }
                sb.append(' ');
            }
            System.out.println("range t" + sfx + " " + sb);
        }
        f("%tY", new Acc(true, 1000000000, false)); f("%tY", new Acc(true, 1000000001, false)); f("%tC", new Acc(true, 1000000000, false)); f("%ty", new Acc(true, 1000000001, false));
        f("%tr", new Acc(true, 0, false)); f("%tr", new Acc(true, 1, false)); f("%tr", new Acc(true, 2, false)); f("%Tp", new Acc(true, 2, false));
        f("%tR", new Acc(true, 24, false)); f("%tT", new Acc(true, 60, false)); f("%tD", new Acc(true, 0, false));
        f("%tZ %<tz", LocalDateTime.of(2020, 1, 2, 3, 4));
        f("%tH %tM", new Date(1577934245123L), new Date(1577934245123L));
        f("%tZ %<tz", new Date(0));
        f("%s %S %10s|", new Date(0), new Date(0), new Date(0));
        // Formatter objects and printf (X5, X6)
        StringBuilder sb2 = new StringBuilder();
        Formatter fm = new Formatter(sb2, Locale.US);
        fm.format("%tF %<tT", ms).format(" | %tB", 0L);
        System.out.println(fm + " " + sb2.length());
        System.out.println(new Formatter(Locale.ROOT).format("%tA %<tB", ms));
        System.out.println(new Formatter(Locale.UK).format("%tA %<tB %<tp", ms));
        System.out.println(new Formatter(Locale.ENGLISH).format("%1$tb %1$td, %1$tY %1$tl:%1$tM:%1$tS %1$Tp%n", new Date(0)).toString().trim());
        System.out.printf(Locale.US, "%tc%n", new Date(ms));
        System.out.printf(Locale.US, "%tT.%<tL%n", ms);
        System.out.println(String.format("%tY", ms) + " " + String.format(Locale.US, "%tY", ms) + " " + String.format((Locale) null, "%tY", ms));
        System.out.println(new Formatter().format("%tB %<tA", ms));
    }
}
