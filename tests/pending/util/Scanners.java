package util;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * Scanner tokens, delimiters, lines, find/skip/match, closing, sources and I/O errors.
 * Rules SC1-SC9 and SC20-SC23 of docs/design/util-text.md. Every scanner uses Locale.ROOT.
 */
public class Scanners {
    static Scanner s(String t) { return new Scanner(t).useLocale(Locale.ROOT); }

    static String show(Object o) {
        String t = String.valueOf(o);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 || c > 0x7e) b.append(String.format("<%x>", (int) c)); else b.append(c);
        }
        return b.toString();
    }

    static String tokens(Scanner q) {
        StringBuilder b = new StringBuilder();
        while (q.hasNext()) b.append('[').append(show(q.next())).append(']');
        return b.toString();
    }

    static String lines(Scanner q) {
        StringBuilder b = new StringBuilder();
        while (q.hasNextLine()) b.append('[').append(show(q.nextLine())).append(']');
        return b.toString();
    }

    interface Op { Object run() throws Exception; }

    static String ex(Op r) {
        try {
            return show(r.run());
        } catch (Exception e) {
            return e.getClass().getName() + ": " + show(e.getMessage());
        }
    }

    /** A reader that hands out its text in pieces of the given sizes (SC8). */
    static class Chunky extends Reader {
        final String text;
        final int[] sizes;
        int pos;
        int k;
        boolean closed;

        Chunky(String text, int... sizes) { this.text = text; this.sizes = sizes; }

        public int read(char[] b, int off, int len) {
            if (pos >= text.length()) return -1;
            int n = Math.min(Math.min(len, sizes[k++ % sizes.length]), text.length() - pos);
            text.getChars(pos, pos + n, b, off);
            pos += n;
            return n;
        }

        public void close() { closed = true; }
    }

    /** Hands out "ab cd" and then fails on every read (SC9). */
    static class Failing extends Reader {
        int reads;

        public int read(char[] b, int off, int len) throws IOException {
            if (reads++ == 0) {
                "ab cd".getChars(0, 5, b, off);
                return 5;
            }
            throw new IOException("boom " + reads);
        }

        public void close() {}
    }

    static long seed = 0x5eed5L;

    static int rnd(int n) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % n);
    }

    /** A fixed list of operations applied to one scanner; returns what each one gave. */
    static String script(Scanner q, int[] ops) {
        StringBuilder b = new StringBuilder();
        for (int op : ops) {
            try {
                switch (op) {
                    case 0: b.append("n").append(q.next()); break;
                    case 1: b.append("i").append(q.hasNextInt()); break;
                    case 2: b.append("I").append(q.nextInt()); break;
                    case 3: b.append("L").append(q.nextLine()); break;
                    case 4: b.append("h").append(q.hasNextLine()); break;
                    case 5: b.append("f").append(q.findInLine("a+1")); break;
                    case 6: b.append("d").append(q.nextDouble()); break;
                    case 7: b.append("w").append(q.findWithinHorizon("1-", 4)); break;
                    case 8: b.append("s").append(q.hasNext("a.*")); break;
                    default: b.append("H").append(q.hasNext()); break;
                }
            } catch (RuntimeException e) {
                b.append(e.getClass().getSimpleName());
            }
            b.append('|');
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        // SC1: the default delimiter is \p{javaWhitespace}+; U+00A0 is not white space, U+2003 and U+001C are.
        System.out.println("ws " + tokens(s("  a\tb\n\nc  d e f\u001cg\u000bh  ")));
        // SC2: one delimiter match is skipped before a token; empty tokens appear between adjacent matches.
        System.out.println("comma " + tokens(s(",a,,b,").useDelimiter(",")) + " " + tokens(s(",a,,b,").useDelimiter(",+")) + " " + tokens(s(",,,").useDelimiter(",")) + " " + tokens(s(",,,x").useDelimiter(",")));
        System.out.println("empty delimiter " + tokens(s("abc").useDelimiter("")) + " lookahead " + tokens(s("aBcDe").useDelimiter("(?=[A-Z])")) + " multi " + tokens(s("a--b-c---d").useDelimiter("--")));
        System.out.println("no token " + tokens(s("")) + "|" + tokens(s("   ")) + "|" + ex(() -> s("").next()) + "|" + ex(() -> s("  ").next()) + "|" + ex(() -> s("").nextInt()) + "|" + ex(() -> s("").nextDouble()) + "|" + ex(() -> s("").nextBoolean()));
        // SC3: delimiter() and useDelimiter.
        System.out.println("delimiter " + s("x").delimiter() + " " + s("x").useDelimiter(Pattern.compile("[,;]")).delimiter() + " " + s("x").useDelimiter("a|b").delimiter().pattern() + " " + s("x").useDelimiter("x").delimiter().flags());
        // SC4: next(pattern) and hasNext(pattern) match the whole token.
        System.out.println("pattern " + s("abc").hasNext("ab") + s("abc").hasNext("a.c") + s("abc").hasNext("abc|ab") + " " + ex(() -> s("abc").next("ab")) + " " + ex(() -> s("abc def").next("[a-c]+")) + " " + ex(() -> s("").hasNext("x")) + " " + ex(() -> s("").next("x")));
        Scanner q = s("abc 42");
        q.next("a(b)c");
        System.out.println("pattern groups " + q.match().group(1) + " " + q.match().start(1) + " " + q.match().end() + " " + q.match().groupCount());
        q = s("ab12 x");
        System.out.println("pattern then token " + q.hasNext("[a-z]+") + " " + q.hasNext("[a-z]+\\d+") + " " + q.next() + " " + q.next());
        // SC5: lines, every line terminator, a final line without one.
        System.out.println("lines " + lines(s("a\nb\r\nc\rd e f\u0085g\n\nh\r\n")) + " " + lines(s("\n")) + "|" + lines(s("x")) + "|" + lines(s("\r\r\n")) + "|" + lines(s("")) + "|" + lines(s("\n\r")) + "|" + lines(s("a\u000bb\fc")));
        System.out.println("nextLine none " + ex(() -> s("").nextLine()) + " | " + ex(() -> { Scanner z = s("a"); z.nextLine(); return z.nextLine(); }));
        q = s("12 rest of line\nnext");
        System.out.println("int then line " + q.nextInt() + "[" + q.nextLine() + "][" + q.nextLine() + "]");
        q = s("12\nx");
        System.out.println("int then line2 " + q.nextInt() + "[" + q.nextLine() + "][" + q.next() + "]");
        q = s("a b\nc");
        q.next();
        System.out.println("line match " + show(q.nextLine()) + " " + show(q.match().group()) + " " + q.match().start() + " " + q.match().end());
        // SC6: findInLine, findWithinHorizon, skip.
        q = s("a1b2c3 d4\nz9");
        System.out.println("findInLine " + q.findInLine("\\d") + q.findInLine("[a-z]\\d") + " " + q.findInLine("d\\d") + " " + q.findInLine("z") + " " + q.next());
        q = s("abc\ndef");
        System.out.println("findInLine none " + q.findInLine("x") + " " + q.next() + " " + q.findInLine(Pattern.compile("e")) + " " + q.findInLine("f") + " " + q.hasNext());
        q = s("x\ny");
        System.out.println("findInLine newline " + show(q.findInLine("x\\s")) + " " + show(q.findInLine("\\s*")) + " " + q.next());
        q = s("hello world foo");
        System.out.println("horizon " + q.findWithinHorizon("wor", 5) + " " + q.findWithinHorizon("wor", 0) + " " + q.next() + " " + q.findWithinHorizon("o", 3) + " " + q.findWithinHorizon("fo", 2) + " " + q.findWithinHorizon("o+", 0));
        System.out.println("horizon errors " + ex(() -> s("x").findWithinHorizon("x", -1)) + " | " + ex(() -> s("ab").findWithinHorizon("b", 1)));
        q = s("   abc def");
        System.out.println("skip " + q.skip("\\s*").next() + " " + ex(() -> s("abc").skip("b")) + " " + s("abc").skip("a").next() + " " + s("abc").skip("x*").next() + " " + ex(() -> s("abc").skip("abc").next()));
        final Scanner kp = s("aab");
        System.out.println("skip keeps position " + ex(() -> kp.skip("b")) + " " + kp.next());
        // SC7: match() after each kind of call.
        q = s("42 abc");
        q.nextInt();
        System.out.println("match int " + q.match().group() + " " + q.match().start() + " " + q.match().end());
        System.out.println("match none " + ex(() -> s("x").match()) + " | " + ex(() -> { Scanner z = s("x"); z.hasNext(); return z.match().group(); }) + " | " + ex(() -> { Scanner z = s("x"); z.next(); z.hasNext(); return z.match().group(); }) + " | " + ex(() -> { Scanner z = s("ab"); z.findInLine("z"); return z.match(); }) + " | " + ex(() -> { Scanner z = s("ab"); z.skip("a"); return z.match().group(); }));
        // SC20: close, then which calls throw "Scanner closed".
        Chunky src = new Chunky("x y", 2);
        Scanner c = new Scanner(src).useLocale(Locale.ROOT);
        c.next();
        c.close();
        c.close();
        System.out.println("closed source " + src.closed);
        System.out.println("closed " + ex(c::next) + " | " + ex(c::hasNext) + " | " + ex(c::nextLine) + " | " + ex(c::hasNextLine) + " | " + ex(c::nextInt) + " | " + ex(c::hasNextInt) + " | " + ex(() -> c.findInLine("x")) + " | " + ex(() -> c.findWithinHorizon("x", 0)) + " | " + ex(() -> c.skip("x")) + " | " + ex(() -> c.hasNext("y")));
        System.out.println("closed still fine " + ex(c::delimiter) + " | " + ex(c::locale) + " | " + ex(c::radix) + " | " + ex(() -> c.useDelimiter(",").delimiter()) + " | " + ex(c::ioException) + " | " + ex(() -> c.match().group()) + " | " + ex(() -> c.reset().radix()) + " | " + ex(() -> c.useRadix(8).radix()));
        // SC21: argument checks.
        System.out.println("nulls " + ex(() -> new Scanner((String) null)) + " | " + ex(() -> new Scanner((Readable) null)) + " | " + ex(() -> new Scanner((InputStream) null)) + " | " + ex(() -> s("x").useDelimiter((String) null)) + " | " + ex(() -> s("x").useDelimiter((Pattern) null)) + " | " + ex(() -> s("x").next((String) null)) + " | " + ex(() -> s("x").hasNext((Pattern) null)) + " | " + ex(() -> s("x").useLocale(null)) + " | " + ex(() -> s("x").findInLine((String) null)) + " | " + ex(() -> s("x").skip((String) null)) + " | " + ex(() -> s("x").findWithinHorizon((Pattern) null, 0)));
        System.out.println("charset " + ex(() -> new Scanner(new ByteArrayInputStream(new byte[0]), "bogus")) + " | " + ex(() -> new Scanner(new ByteArrayInputStream(new byte[0]), (String) null)));
        System.out.println("remove " + ex(() -> { s("x").remove(); return "ok"; }) + " iterator " + (s("x") instanceof Iterator));
        System.out.println("bad delimiter " + ex(() -> s("x").useDelimiter("(")).replaceAll("\\s+", " "));
        // SC22: reset restores the delimiter and radix (and the default locale, not printed: it varies).
        System.out.println("reset " + s("x").useDelimiter(",").useRadix(7).reset().delimiter() + " " + s("x").useRadix(7).reset().radix() + " " + (s("x").reset() != null));
        // SC23: toString of a fresh scanner under ROOT and US.
        System.out.println("toString " + show(s("x")));
        System.out.println("toString US " + show(new Scanner("x").useLocale(Locale.US)));
        System.out.println("toString radix/delim " + show(s("x").useDelimiter(",").useRadix(16)));
        // SC9: an IOException ends the input; ioException() keeps the first one; no further reads.
        Failing f = new Failing();
        q = new Scanner(f);
        System.out.println("io before " + q.ioException());
        System.out.println("io tokens " + q.next() + " " + q.hasNext() + " " + q.next() + " " + q.hasNext() + " " + q.ioException() + " reads " + f.reads);
        System.out.println("io again " + q.hasNext() + " " + q.hasNextLine() + " " + ex(q::next) + " " + q.ioException() + " reads " + f.reads);
        // SC8: InputStream sources decode with the named charset, also one byte at a time; bad bytes become U+FFFD.
        final byte[] utf = "héllo w€rld 😀 x".getBytes("UTF-8");
        InputStream one = new InputStream() {
            int i;
            public int read() { return i < utf.length ? utf[i++] & 0xff : -1; }
            public int read(byte[] b, int off, int len) {
                if (i >= utf.length) return -1;
                b[off] = utf[i++];
                return 1;
            }
        };
        System.out.println("utf8 bytewise " + tokens(new Scanner(one, "UTF-8")));
        System.out.println("latin1 " + show(new Scanner(new ByteArrayInputStream(utf), "ISO-8859-1").nextLine()));
        System.out.println("malformed " + show(new Scanner(new ByteArrayInputStream(new byte[] {'a', (byte) 0xff, ' ', 'b', (byte) 0xc3}), "UTF-8").useDelimiter(" ").next()) + " " + tokens(new Scanner(new ByteArrayInputStream(new byte[] {'a', ' ', (byte) 0xe2, (byte) 0x82}), "UTF-8")));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append((char) ('a' + i % 26));
        q = new Scanner(new StringReader(big + " tail\n" + big + "\nlast"));
        System.out.println("long token " + q.next().length() + " " + q.next() + " " + q.nextLine().length() + " " + q.nextLine().length() + " " + q.nextLine());
        q = new Scanner(new Chunky("a\r\nb\rc", 2));
        System.out.println("crlf split " + lines(q));
        q = new Scanner(new Chunky("x1-y", 1));
        System.out.println("horizon chunked " + q.findWithinHorizon("1-", 4) + " " + q.next());
        // SC8: fuzz. A string source and a one-char reader give the same results (inputs end with a token).
        String alpha = "a1 ,\r\n-. ";
        int same = 0;
        long hash = 0;
        int shown = 0;
        for (int t = 0; t < 2500; t++) {
            StringBuilder b = new StringBuilder();
            int len = rnd(24);
            for (int i = 0; i < len; i++) b.append(alpha.charAt(rnd(alpha.length())));
            b.append('a');
            String text = b.toString();
            int d = rnd(3);
            String delim = d == 0 ? "," : d == 1 ? "\r\n|-" : null;
            int[] ops = new int[12];
            for (int i = 0; i < ops.length; i++) ops[i] = rnd(10);
            Scanner x = s(text);
            Scanner y = new Scanner(new Chunky(text, 1 + rnd(3), 1)).useLocale(Locale.ROOT);
            if (delim != null) {
                x.useDelimiter(delim);
                y.useDelimiter(delim);
            }
            String r1 = script(x, ops);
            String r2 = script(y, ops);
            if (r1.equals(r2)) same++;
            hash = hash * 31 + r1.hashCode();
            if (shown < 25 && t % 97 == 0) {
                shown++;
                System.out.println("fuzz " + show(delim) + " [" + show(text) + "] " + show(r1));
            }
        }
        System.out.println("fuzz chunked same " + same + " of 2500, hash " + hash);
        System.out.println("done");
    }
}
