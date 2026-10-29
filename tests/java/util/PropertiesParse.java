package util;

import java.io.*;
import java.util.*;

/**
 * Properties.load, the parser: continuation lines, escapes, separators, line breaks, malformed
 * \\u escapes, P7a's empty logical lines and P20's state that outlives a line (backslash parity
 * carried by comments, and the reused line buffer's leftovers). Rules P1-P8, P7a and P20 of
 * docs/design/util-text.md. Each case loads through a StringReader, a one-char Reader and
 * ISO 8859-1 bytes.
 */
public class PropertiesParse {
    /** Source shorthand so the cases stay readable: ~ is a backslash, | is LF, ^ is CR. */
    static String t(String s) {
        return s.replace('~', '\\').replace('|', '\n').replace('^', '\r');
    }

    static String show(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '<' || c == '{' || c == '}') b.append(String.format("<%x>", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    static String dump(Properties p) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Object, Object> e : new TreeMap<Object, Object>(p).entrySet()) {
            b.append('{').append(show((String) e.getKey())).append('=').append(show((String) e.getValue())).append('}');
        }
        return b.toString();
    }

    /** Hands out its text one char per read. */
    static class OneChar extends Reader {
        final String text;
        int pos;
        OneChar(String text) { this.text = text; }
        public int read(char[] b, int off, int len) {
            if (pos >= text.length()) return -1;
            b[off] = text.charAt(pos++);
            return 1;
        }
        public void close() {}
    }

    /** The table one load gives, with the exception if it threw (the pairs before it stay). */
    static String load(String text, int mode) {
        Properties p = new Properties();
        try {
            if (mode == 0) p.load(new StringReader(text));
            else if (mode == 1) p.load(new OneChar(text));
            else p.load(new ByteArrayInputStream(text.getBytes("ISO-8859-1")));
        } catch (Exception e) {
            return e.getClass().getName() + ": " + e.getMessage() + " " + dump(p);
        }
        return dump(p);
    }

    /** All three modes; one result when they agree (P7, P8), else each one. */
    static String all(String text) {
        String a = load(text, 0), b = load(text, 1), c = load(text, 2);
        if (a.equals(b) && b.equals(c)) return a;
        return "reader " + a + " | one-char " + b + " | bytes " + c;
    }

    static void cases(String title, String... texts) {
        System.out.println("== " + title);
        for (String s : texts) {
            String text = t(s);
            System.out.println(show(text) + "  ->  " + all(text));
        }
    }

    static String repeat(char c, int n) {
        char[] a = new char[n];
        Arrays.fill(a, c);
        return new String(a);
    }

    /** P20: a \\u at the end of a line reads an earlier, longer line's chars at many lengths. */
    static void leftoverLengths(String prefix) {
        StringBuilder b = new StringBuilder();
        int bad = 0;
        for (int n = 3; n <= 700; n += (n < 140 ? 1 : 7)) {
            String text = prefix + repeat('q', n) + "abcd\n=" + repeat('k', n - 3) + "\\u";
            String got = load(text, n % 3);
            String want = "{=" + repeat('k', n - 3) + "<abcd>}";
            if (prefix.isEmpty()) want = want + "{" + repeat('q', n) + "abcd=}";
            if (!got.equals(want)) {
                bad++;
                if (bad <= 3) b.append(" n=").append(n).append(' ').append(got.length() > 60 ? got.substring(got.length() - 60) : got);
            }
        }
        System.out.println("leftover \\u after " + (prefix.isEmpty() ? "a pair" : "a comment") + ", lengths 3-700: " + bad + " differ" + b);
    }

    /** P20: a lone backslash left by the parity carry takes the leftover at its place. */
    static void loneBackslashLengths() {
        StringBuilder b = new StringBuilder();
        for (int k = 1; k <= 41; k += 2) {
            String pad = repeat('p', k) + "Z";
            String text = "#" + pad + "\\\n" + repeat('\\', k);
            b.append(k).append(':').append(load(text, k % 3)).append(' ');
        }
        System.out.println("lone backslash after #p..pZ~: " + b);
        StringBuilder z = new StringBuilder();
        for (int k = 1; k <= 301; k += 30) {
            String text = "#\\\n" + repeat('\\', k);
            String got = load(text, 0);
            z.append(k).append(':').append(got.endsWith("<0>=}") ? "nul" : got).append(' ');
        }
        System.out.println("lone backslash past anything written: " + z);
    }

    static long seed = 0x2545F4914F6CDD1DL;

    static int next(int bound) {
        seed ^= seed << 13;
        seed ^= seed >>> 7;
        seed ^= seed << 17;
        return (int) ((seed >>> 1) % bound);
    }

    static final String[] ATOMS = {"\\", "\\", "\\", "\\\\", "\\u", "\\u00e9", "\\u0041", "\\uZZ", "\\u0",
        "=", ":", " ", "\t", "\f", "#", "!", "\r", "\n", "\r\n", "\n", "a", "b", "u", "t", "0", "f",
        "\u00e9", "\u0000", "#ab0\\\n", "#F\\\\\\\n", "\\\\\\", "qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq", "abcdef0123"};

    /** P8, P20: random texts over the risky atoms; every table and error goes into the hash. */
    static void fuzz(int count) {
        int h = 0, agree = 0, threw = 0;
        for (int i = 0; i < count; i++) {
            StringBuilder b = new StringBuilder();
            int atoms = 1 + next(30);
            for (int j = 0; j < atoms; j++) b.append(ATOMS[next(ATOMS.length)]);
            String text = b.toString();
            String r = load(text, 0);
            if (r.equals(load(text, 1)) && r.equals(load(text, 2))) agree++;
            if (r.startsWith("java.")) threw++;
            h = h * 31 + r.hashCode();
        }
        System.out.println("fuzz " + count + ": modes agree " + agree + ", threw " + threw + ", hash " + Integer.toHexString(h));
    }

    public static void main(String[] args) throws IOException {
        cases("separators (P3)",
            "a=1", "a:1", "a 1", "a\t1", "a\f1", "a = 1", "a :=1", "a=:1", "a  \t  b c ", "a", "a=", "=1", ":1",
            "  \t\f a=1", "a==1", "a  ", "a=1  ");
        cases("escapes (P4)",
            "a~ b=1", "a~=b=1", "a~:b=1", "~#a=1", "~!a=1", "~~=1", "a=~t~n~r~f", "a=~x~y~ ~=~:", "~u0041~u00e9=~u20AC~u20ac",
            "a=~uD83D~uDE00", "a=~ud800", "~t~n=1", "a=~~u0041", "a=~~~u0041");
        cases("malformed \\u (P5)",
            "a=~u004", "a=~u00G1", "a=~uzzzz", "a=~u", "~u12=1", "a=~U0041", "a=~u+041", "a=~u 041", "#~u12|a=1",
            "x=1|a=~u00G1|y=2", "a=~u\uff10041");
        cases("line breaks (P1)",
            "a=1|b=2", "a=1^b=2", "a=1^|b=2", "a=1|^b=2", "a=1||b=2", "a=1^^b=2", "a=1|  |b=2", "a=1\u2028b=2", "a=1\u000bb=2",
            "#c|a=1", "!c^a=1", "  #c^|a=1", "\t!c|a=1", "a=1#c", "a=1|#c|b=2", "a=\u0000");
        cases("continuations (P2)",
            "a=1~|   2", "a=1~^  2", "a=1~^|\t2", "a=1~|# not comment", "#a~|b=1", "a=1~", "a=1~|~| x", "a=b~||c=d",
            "a=b~|  |c=d", "a~|=b", "a~|~|~|b=c", "a=1~~|b=2", "a=1~~~|2", "a=~|~|", "k~|e~|y=v~|al", "a=1~^ |2",
            "a=1~^\t|2", "a=1~^^b=2", "~|", "a~ ~|b");
        cases("empty logical lines (P7a)",
            "~|", "~^", "~^|", "~| ", "~|\t", "~||x", "~^^x", "~^|^|x", "~|~", "~|~|", "~|~^", "~|~^|", "~^~^", "a~|",
            "a~| ", "a~^|", "~", "~~", "~~~", "~|  |#x=1", "~||#x=1");
        cases("parity carried by comments (P20)",
            "#x~|~|b=1", "#x~|~", "#x~~|~|b=1", "#x~~~|~|b=1", "#x~|~~|b=1", "#x~|~~~|b=1", "#x~|#|~|b=1",
            "#x~|!|~|b=1", "#x~|#~|~|b=1", "#x~|#y|~|b=1", "#x~||~|b=1", "#x~|   |~|b=1", "#x~|  ~|b=1",
            "#x~^|~|b=1", "#x~^~^b=1", "#x~|a~|b", "#x~|a=~|b", "!x~|~~~~~|b=1", "#~|~|b=1");
        cases("leftovers (P20)",
            "#abcdefgh|a=~u0", "aaaaaaaaa|a=~u0", "aaaaaaaaa|a=~u00", "a=1234567|b=~u", "#Xu0041~|~", "#Xt~|~",
            "#X=~|~", "#X ~|~", "#Xn~|~", "#Xu00~|~", "#abcd~|~~~", "#ab~|~~~", "#  ab~|~~~", "#~|~", "x=~u0041|~u12",
            "abcdef|~u12=1", "abcdefgh|#|~u12", "abc123456|#~|~");
        leftoverLengths("");
        leftoverLengths("#");
        loneBackslashLengths();
        System.out.println("fresh buffer per load: " + load(t("#x~"), 0) + " then " + load(t("~u"), 0));

        final List<String> puts = new ArrayList<String>();
        Properties spy = new Properties() {
            public synchronized Object put(Object k, Object v) { puts.add(k + "=" + v); return super.put(k, v); }
        };
        spy.put("a", "old");
        puts.clear();
        spy.load(new StringReader(t("a=1|b=2|a=3|#c|c")));
        System.out.println("put calls (P6): " + puts + " " + dump(spy));

        System.out.println("== reads and puts (P8): each part is one read; - is -1, 0 is 0");
        String[][] parts = {{"a=1|b=2|c=3"}, {"a|"}, {"a^|b"}, {"a^", "|b"}, {"a~||", "-", "b"}, {"#c|"},
            {"a=1|x", "0", "y|z", "0"}, {"a", "-", "b"}, {"#c", "-", "b"}, {"a~", "-", "~"}, {"~", "-", "~~"},
            {"qqqqqqq~", "-", "=~u"}, {"~|", "-", "b"}, {"~| ", "-", "b"}, {"0"}};
        for (String[] ps : parts) {
            String[] text = new String[ps.length];
            for (int i = 0; i < ps.length; i++) text[i] = t(ps[i]);
            System.out.println(show(Arrays.toString(text)) + " : " + logged(text));
        }

        System.out.println("bytes as ISO 8859-1 (P7): " + all("a=\u00e9\u00ff~u00e9".replace('~', '\\')) + " utf-8 "
            + loadBytes("k=\u00e9\u20ac".getBytes("UTF-8")) + " high " + loadBytes(new byte[] {(byte) 0x80, '=', (byte) 0xff}));
        fuzz(1500);
    }

    /** The reads and puts of one load, the reader answering with `parts` in turn. */
    static String logged(final String[] parts) {
        final StringBuilder log = new StringBuilder();
        Reader r = new Reader() {
            int i;
            public int read(char[] c, int o, int n) {
                if (i == 0) log.append("read(").append(c.length).append(',').append(o).append(',').append(n).append(") ");
                if (i >= parts.length) { log.append("end "); return -1; }
                String s = parts[i++];
                if (s.equals("-")) { log.append("-1 "); return -1; }
                if (s.equals("0")) { log.append("0 "); return 0; }
                s.getChars(0, s.length(), c, o);
                log.append(s.length()).append(' ');
                return s.length();
            }
            public void close() { log.append("close "); }
        };
        Properties p = new Properties() {
            public synchronized Object put(Object k, Object v) { log.append("put[" + k + "=" + v + "] "); return super.put(k, v); }
        };
        try {
            p.load(r);
        } catch (Exception e) {
            log.append(e.getMessage());
        }
        return show(log.toString());
    }

    static String loadBytes(byte[] b) throws IOException {
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(b));
        return dump(p);
    }
}
