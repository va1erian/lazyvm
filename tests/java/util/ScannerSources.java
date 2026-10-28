package util;

import java.io.*;
import java.util.*;
import java.util.function.Function;

/**
 * How far a Scanner reads its source (util-text.md SC9 and section 4). After a source reports
 * its end or fails, it is never read again, even if it would hand out more. And on a source that
 * never ends and never says it is ready, the scanner stops reading soon after the text that
 * settles the answer: across a read boundary, after a long skip, a long delimiter run, a long
 * periodic run, and after the buffer has dropped passed text. Positions are not printed.
 */
public class ScannerSources {
    static String rep(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    static String shown(Object res) {
        if (!(res instanceof String)) return String.valueOf(res);
        String t = (String) res;
        return t.length() + " chars, ends [" + t.substring(Math.max(0, t.length() - 6)).replace("\n", "\\n") + "]";
    }

    /**
     * Says it is ready, hands out 100 chars per read, then ends (or throws) once, then would hand
     * out more text, as a console can after an end-of-file key.
     */
    static void afterEnd(String name, String text, boolean fail, Function<Scanner, Object> op) {
        int[] st = {0, 0}; // chars given, reads after the end
        Reader r = new Reader() {
            boolean done;
            public int read(char[] c, int off, int len) throws IOException {
                if (done) {
                    st[1]++;
                    String more = " LEAK more\n";
                    int n = Math.min(len, more.length());
                    more.getChars(0, n, c, off);
                    return n;
                }
                if (st[0] >= text.length()) {
                    done = true;
                    if (fail) throw new IOException("boom");
                    return -1;
                }
                int n = Math.min(100, Math.min(len, text.length() - st[0]));
                text.getChars(st[0], st[0] + n, c, off);
                st[0] += n;
                return n;
            }
            public boolean ready() { return true; }
            public void close() {}
        };
        Scanner s = new Scanner(r);
        Object res;
        try {
            res = op.apply(s);
        } catch (RuntimeException e) {
            res = e.getClass().getName();
        }
        System.out.println(name + ": " + shown(res) + ", reads after end " + st[1] + ", ioException " + s.ioException());
    }

    /**
     * Hands out `prefix`, then `fill` for ever; never ready. Each read gives at most `step` chars,
     * or, while fewer than `bulk` chars have been given, as many as asked.
     */
    static final class Endless extends Reader {
        final String prefix;
        final char fill;
        final int step;
        final int bulk;
        long given;

        Endless(String prefix, char fill, int step, int bulk) {
            this.prefix = prefix;
            this.fill = fill;
            this.step = step;
            this.bulk = bulk;
        }

        public int read(char[] c, int off, int len) {
            int n = given < bulk ? (int) Math.min(len, bulk - given) : Math.min(step, len);
            for (int i = 0; i < n; i++, given++) c[off + i] = given < prefix.length() ? prefix.charAt((int) given) : fill;
            return n;
        }

        public void close() {}

        /** Whether the scanner stopped within 1,000 chars of the end of the prefix. */
        String stopped() {
            return "read past the text by under 1000 chars " + (given - prefix.length() < 1000);
        }
    }

    public static void main(String[] args) {
        // SC9: nothing is read after the end, or after a failure.
        for (int len : new int[] {5000, 12000}) {
            afterEnd("token " + len, rep('a', len), false, Scanner::next);
            afterEnd("line " + len, rep('a', len), false, Scanner::nextLine);
            afterEnd("skip " + len, rep('a', len), false, s -> { s.skip("a*"); return s.hasNext() + ""; });
            afterEnd("horizon " + len, rep('a', len), false, s -> s.findWithinHorizon("a+", 0));
            afterEnd("failure " + len, rep('a', len), true, Scanner::next);
        }
        // A two-char delimiter split across a read boundary (10,047 = 156 * 64 + 63).
        Endless w = new Endless(rep('a', 10047) + "--", 'c', 64, 0);
        System.out.println("delimiter across reads: " + shown(new Scanner(w).useDelimiter("--").next()) + ", " + w.stopped());
        // A long skip, a long run of blanks before a token, and a long periodic delimiter run.
        Endless k = new Endless(rep('a', 10000) + "b", 'c', 64, 0);
        new Scanner(k).skip("a*");
        System.out.println("skip: " + k.stopped());
        Endless bl = new Endless(rep(' ', 10000) + "x ", 'c', 64, 0);
        System.out.println("blanks: " + shown(new Scanner(bl).next()) + ", " + bl.stopped());
        Endless pr = new Endless("y" + String.join("", Collections.nCopies(5000, ",;")) + "z,;", 'c', 64, 0);
        Scanner ps = new Scanner(pr).useDelimiter("(,;)+");
        System.out.println("periodic run: " + ps.next() + " " + ps.next() + ", " + pr.stopped());
        // A period of 7, so a read can start the window in any of 7 phases.
        Endless p7 = new Endless("y" + String.join("", Collections.nCopies(1500, ",;:.!?-")) + "z,;:.!?-", 'c', 70, 0);
        Scanner s7 = new Scanner(p7).useDelimiter("(,;:\\.!\\?-)+");
        System.out.println("period 7 run: " + s7.next() + " " + s7.next() + ", " + p7.stopped());
        // A token that ends after the buffer has dropped passed text (40,000 + 30,000 chars).
        Endless st = new Endless(rep('a', 40000) + " " + rep('b', 30000) + " ", 'c', 1000, 60000);
        Scanner ss = new Scanner(st);
        System.out.println("after dropping text: " + shown(ss.next()) + " " + shown(ss.next()) + ", " + st.stopped());
        System.out.println("done");
    }
}
