// TIMEOUT: 30
package util;

import java.io.*;
import java.util.*;

/**
 * Scanner on long tokens, lines, delimiter runs, skips and failed searches (200,000 chars for the
 * token, line and skip, from a String and from a Reader; 40,000 from an InputStream, a Reader and
 * a Readable that hand out 64 per read and are never ready). It pins the results; that each case
 * stays linear, not quadratic, in the length shows as the test finishing well within its time
 * limit. Positions are not printed: the oracle reports them relative to its own buffer
 * (util-text.md section 4).
 */
public class ScannerLong {
    static final int N = 200000;

    interface Op { Object run(Scanner s); }

    static String rep(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    static String describe(Object r) {
        if (r instanceof String) {
            String t = (String) r;
            return "len " + t.length() + (t.isEmpty() ? "" : " ends " + t.charAt(t.length() - 1));
        }
        return String.valueOf(r);
    }

    /** One case from a String and, when `reader`, from a Reader too. */
    static void row(String name, String text, boolean reader, Op op) {
        String a = describe(op.run(new Scanner(text)));
        String b = reader ? describe(op.run(new Scanner(new StringReader(text)))) : a;
        System.out.println(name + ": " + a + (a.equals(b) ? reader ? " (reader same)" : "" : " (reader " + b + ")"));
    }

    /** available() stays 0, so its reader is never ready; each read gives at most 64 bytes. */
    static InputStream shortStream(String text) {
        byte[] b = text.getBytes();
        return new InputStream() {
            int p;
            public int read() { return p < b.length ? b[p++] & 0xff : -1; }
            public int read(byte[] d, int off, int len) {
                if (p >= b.length) return -1;
                int n = Math.min(64, Math.min(len, b.length - p));
                System.arraycopy(b, p, d, off, n);
                p += n;
                return n;
            }
        };
    }

    /** ready() is false; each read gives at most 64 chars. */
    static Reader shortReader(String text) {
        return new Reader() {
            int p;
            public int read(char[] d, int off, int len) {
                if (p >= text.length()) return -1;
                int n = Math.min(64, Math.min(len, text.length() - p));
                text.getChars(p, p + n, d, off);
                p += n;
                return n;
            }
            public void close() {}
        };
    }

    /** A bare Readable; each read gives at most 64 chars. */
    static Readable shortReadable(String text) {
        return new Readable() {
            int p;
            public int read(java.nio.CharBuffer cb) {
                if (p >= text.length()) return -1;
                int n = Math.min(64, Math.min(cb.remaining(), text.length() - p));
                cb.put(text, p, p + n);
                p += n;
                return n;
            }
        };
    }

    static String repLines(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(i % 10).append(i % 3 == 0 ? "\r\n" : "\n");
        return b.toString();
    }

    public static void main(String[] args) {
        String as = rep('a', N);
        row("token", as + "b c", true, s -> s.next() + "|" + s.next());
        row("line", as + "\nb", true, s -> s.nextLine() + "|" + s.nextLine());
        row("skip", as + "b", true, s -> s.skip("a*").next());
        String m = rep('a', N / 4);
        row("line crlf", m + "\r\nb", false, s -> s.nextLine() + "|" + s.nextLine());
        row("blanks", rep(' ', N / 4) + "x", false, s -> s.next());
        row("horizon miss", m, false, s -> s.findWithinHorizon("zzz", 0) + "|" + s.hasNextLine());
        row("horizon hit", m + "zzz", false, s -> s.findWithinHorizon("z+", 0) + "|" + s.hasNext());
        row("findInLine miss", m + "\nb", false, s -> s.findInLine("zzz") + "|" + s.hasNextInt());
        row("hasNextInt digits", rep('1', N / 4), false, s -> s.hasNextInt());
        // Sources that hand out 64 chars or bytes per read and never say they are ready.
        String t40 = rep('a', N / 5) + " b";
        String[] kinds = {"stream", "reader", "readable"};
        for (String k : kinds) {
            Scanner s = k.equals("stream") ? new Scanner(shortStream(t40), "UTF-8") : k.equals("reader") ? new Scanner(shortReader(t40)) : new Scanner(shortReadable(t40));
            String a = describe(s.next() + "|" + s.next());
            Scanner l = new Scanner(shortReader(as + "\nb"));
            System.out.println("short reads " + k + ": " + a + (k.equals("reader") ? " line " + describe(l.nextLine()) : ""));
        }
        // An endless source: the token ends after 10,000 chars, then 'c' comes forever, 64 per read.
        // The scanner must stop reading soon after the delimiter, as an interactive one must.
        int[] given = {0};
        Reader endless = new Reader() {
            public int read(char[] d, int off, int len) {
                int n = Math.min(64, len);
                for (int i = 0; i < n; i++, given[0]++) d[off + i] = given[0] < 10000 ? 'a' : given[0] == 10000 ? ' ' : 'c';
                return n;
            }
            public void close() {}
        };
        Scanner e = new Scanner(endless);
        System.out.println("endless source: token " + describe(e.next()) + ", read past it by under 1000 chars " + (given[0] < 11000));
        // The same with a delimiter whose lookahead reaches far past the text just read: the scanner
        // may read further before it notices, but it must notice (LazyVM: when the text doubles).
        int[] given2 = {0};
        Reader endless2 = new Reader() {
            public int read(char[] d, int off, int len) {
                int n = Math.min(64, len);
                for (int i = 0; i < n; i++, given2[0]++) d[off + i] = given2[0] < 10000 ? 'a' : given2[0] == 10000 ? 'x' : 'c';
                return n;
            }
            public void close() {}
        };
        Scanner e2 = new Scanner(endless2).useDelimiter("x(?=c{300})");
        System.out.println("endless source, long lookahead: token " + describe(e2.next()) + ", stopped " + (given2[0] < 100000));
        row("lines", repLines(N / 20), true, s -> {
            int n = 0;
            while (s.hasNextLine()) {
                s.nextLine();
                n++;
            }
            return n;
        });
        System.out.println("done");
    }
}
