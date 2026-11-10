// VMARGS: -Duser.timezone=UTC
package time;

import java.time.*;
import java.time.format.*;
import java.time.temporal.*;

/**
 * DateTimeFormatter.ofPattern with the letters y u M d H m s S, literals and quotes, and format of a
 * LocalDateTime or another accessor (docs/design/time.md, rules F1 to F5).
 */
public class FormatPatterns {
    interface Act { Object run() throws Throwable; }

    static void p(String l, Act a) {
        try {
            System.out.println(l + ": " + a.run());
        } catch (Throwable e) {
            System.out.println(l + ": " + e);
        }
    }

    /** An accessor whose isSupported and getLong answers are chosen by the test. */
    static class Acc implements TemporalAccessor {
        final boolean supported;
        final long value;
        final boolean throwing;

        Acc(boolean supported, long value, boolean throwing) {
            this.supported = supported;
            this.value = value;
            this.throwing = throwing;
        }

        public boolean isSupported(TemporalField f) { return supported; }

        public long getLong(TemporalField f) {
            if (throwing) throw new DateTimeException("custom");
            if (value == Long.MIN_VALUE) throw new UnsupportedTemporalTypeException("Unsupported field: " + f);
            return value;
        }
    }

    static final LocalDateTime A = LocalDateTime.of(2020, 1, 5, 3, 4, 5, 6789000);
    static final LocalDateTime B = LocalDateTime.of(5, 12, 25, 15, 0, 0, 0);
    static final LocalDateTime C = LocalDateTime.of(-5, 7, 31, 0, 0, 59, 120000000);
    static final LocalDateTime D = LocalDateTime.of(12345, 2, 28, 12, 30, 59, 999999999);

    static void row(String pattern) {
        StringBuilder sb = new StringBuilder();
        for (LocalDateTime t : new LocalDateTime[] {A, B, C, D}) {
            try {
                sb.append('[').append(DateTimeFormatter.ofPattern(pattern).format(t)).append(']');
            } catch (Throwable e) {
                sb.append('[').append(e).append(']');
            }
        }
        System.out.println(pattern + " " + sb);
    }

    public static void main(String[] args) {
        // F1: the game's pattern, and the kept letters over four dates
        p("game", () -> DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").format(A));
        p("game 2", () -> DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss").format(LocalDateTime.of(1999, 12, 31, 23, 59, 59)));
        for (String s : new String[] {"y", "yy", "yyy", "yyyy", "yyyyy", "yyyyyy", "yyyyyyyyyy", "u", "uu", "uuu", "uuuu", "uuuuu", "uuuuuu", "M", "MM", "d", "dd", "H", "HH", "m", "mm",
            "s", "ss", "S", "SS", "SSS", "SSSS", "SSSSSS", "SSSSSSSSS", "yyyyMMdd", "HHmmss", "Hmm", "y y", "d/M/u", "uuuu-MM-dd'T'HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss.SSSSSSSSS"}) {
            row(s);
        }
        // F2: years with more digits than the pattern asks for
        for (int y : new int[] {123456, 99999, -99999, 10000, 9999, 0, -1, 1, 999999999, -999999999}) {
            for (String pat : new String[] {"y", "yy", "yyy", "yyyy", "yyyyy", "yyyyyy", "u", "uu", "uuu", "uuuu", "uuuuu", "uuuuuu"}) {
                p("year " + y + " " + pat, () -> DateTimeFormatter.ofPattern(pat).format(LocalDateTime.of(y, 3, 4, 0, 0)));
            }
        }
        p("10 y", () -> DateTimeFormatter.ofPattern("yyyyyyyyyy").format(A));
        p("19 y", () -> DateTimeFormatter.ofPattern("yyyyyyyyyyyyyyyyyyy").format(A));
        p("19 u", () -> DateTimeFormatter.ofPattern("uuuuuuuuuuuuuuuuuuu").format(C));
        p("20 y", () -> DateTimeFormatter.ofPattern("yyyyyyyyyyyyyyyyyyyy"));
        p("20 u", () -> DateTimeFormatter.ofPattern("uuuuuuuuuuuuuuuuuuuu"));
        p("26 y", () -> DateTimeFormatter.ofPattern("yyyyyyyyyyyyyyyyyyyyyyyyyy"));
        // F3: quotes and literals
        for (String s : new String[] {"''''", "''''''", "'''a'", "'a'''", "a''", "''a", "'a''b''c'", "''", "'abc'", "'HH'", "H'H'H", "yyyy'-'MM", "-.: /", "'T'", "'", "'abc", "'''", "A'"}) {
            row(s);
        }
        p("empty", () -> "[" + DateTimeFormatter.ofPattern("").format(A) + "]");
        // F4: pattern errors are raised by ofPattern
        for (String s : new String[] {"ddd", "HHH", "mmm", "sss", "SSSSSSSSSS", "aa", "aaa", "g", "i", "j", "{", "}", "#", "yyyy-MM-dd{", "yyyy#", "]", "yyyy]", "y]y"}) {
            p("ofPattern " + s, () -> DateTimeFormatter.ofPattern(s));
        }
        p("ofPattern null", () -> DateTimeFormatter.ofPattern(null));
        // F5: format of an accessor
        p("format null", () -> DateTimeFormatter.ofPattern("yyyy").format(null));
        // an accessor that cannot give a field throws from getLong; the formatter passes that exception on, at the first such letter
        for (String letter : new String[] {"yyyy", "uuuu", "MM", "dd", "HH", "mm", "ss", "SSS"}) {
            p("format cannot give " + letter, () -> DateTimeFormatter.ofPattern(letter).format(new Acc(true, Long.MIN_VALUE, false)));
        }
        p("format cannot give, later letter", () -> DateTimeFormatter.ofPattern("'x'HH:mm").format(new Acc(true, Long.MIN_VALUE, false)));
        p("format isSupported is not consulted", () -> DateTimeFormatter.ofPattern("yyyy").format(new Acc(false, 2020, false)));
        p("format supported", () -> DateTimeFormatter.ofPattern("yyyy").format(new Acc(true, 2020, false)));
        p("format value 5", () -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").format(new Acc(true, 5, false)));
        p("format getLong throws", () -> DateTimeFormatter.ofPattern("HH").format(new Acc(true, 5, true)));
        p("format wide value", () -> DateTimeFormatter.ofPattern("MM").format(new Acc(true, 123, false)));
        p("format negative value", () -> DateTimeFormatter.ofPattern("HH").format(new Acc(true, -5, false)));
        for (String letters : new String[] {"MM", "dd", "HH", "mm", "ss"}) {
            for (long v : new long[] {-5, -12, -123, -12345}) {
                p("format negative " + letters + " " + v, () -> DateTimeFormatter.ofPattern(letters).format(new Acc(true, v, false)));
            }
        }
        p("LocalDateTime format null", () -> A.format(null));
        p("format wide d", () -> DateTimeFormatter.ofPattern("dd").format(new Acc(true, 1234, false)));
        p("format wide S", () -> DateTimeFormatter.ofPattern("SSS").format(new Acc(true, 999999999, false)));
        p("format negative S", () -> DateTimeFormatter.ofPattern("SSS").format(new Acc(true, -1, false)));
        p("format S truncates", () -> DateTimeFormatter.ofPattern("S SS SSS").format(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 987654321)));
        p("format S zero", () -> DateTimeFormatter.ofPattern("SSSSSSSSS").format(LocalDateTime.of(2020, 1, 1, 0, 0, 0, 5)));
        p("format Month 12", () -> DateTimeFormatter.ofPattern("M MM").format(LocalDateTime.of(2020, 12, 1, 0, 0)));
        p("format midnight", () -> DateTimeFormatter.ofPattern("H HH m s").format(LocalDateTime.of(2020, 12, 1, 0, 0)));
        p("format same twice", () -> {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            return f.format(A) + " " + f.format(B) + " " + f.format(A);
        });
        p("format toString non-null", () -> DateTimeFormatter.ofPattern("yyyy").toString() != null);
    }
}
