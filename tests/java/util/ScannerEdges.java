package util;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * Scanner rules added after review: SC2a, SC6 (empty line), SC7 and SC7a (which calls leave a
 * match), SC21a (constructor and closed-scanner argument order), SC24 (what hasNext calls
 * remember), and the stream-source rereads of requireEnd and horizons. Every input is far below
 * 1 KB, so the printed positions are input offsets on every VM (util-text.md section 4).
 */
public class ScannerEdges {
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

    interface Op { Object run(Scanner q) throws Exception; }

    /** Runs the calls in order on one scanner and shows each result, then match() and the rest. */
    static String go(Scanner q, Op... ops) {
        StringBuilder b = new StringBuilder();
        for (Op o : ops) {
            try {
                Object r = o.run(q);
                b.append(r instanceof Scanner ? "scanner" : show(r));
            } catch (Exception e) {
                b.append(e.getClass().getSimpleName());
            }
            b.append(' ');
        }
        return b + "| " + match(q) + " | rest [" + rest(q) + "]";
    }

    static String match(Scanner q) {
        try {
            MatchResult m = q.match();
            return "match [" + show(m.group()) + "] " + m.start() + "-" + m.end();
        } catch (IllegalStateException e) {
            return "no match";
        }
    }

    static String rest(Scanner q) {
        StringBuilder b = new StringBuilder();
        try {
            while (q.hasNextLine()) b.append(show(q.nextLine())).append('/');
        } catch (IllegalStateException e) {
            b.append("closed");
        }
        return b.toString();
    }

    interface Make { Object run() throws Exception; }

    static String ex(Make m) {
        try {
            Object r = m.run();
            return r instanceof Scanner ? "scanner" : show(r);
        } catch (Exception e) {
            return e.getClass().getName() + ": " + show(e.getMessage());
        }
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

    static Scanner one(String t) { return new Scanner(new OneChar(t)).useLocale(Locale.ROOT); }

    public static void main(String[] args) throws Exception {
        Op d = q -> q.useDelimiter("") != null;
        // SC2a: a failing next passes the delimiter before the token, or the trailing ones (M02).
        System.out.println("SC2a next none " + go(s("a  "), q -> q.next(), q -> q.next(), q -> q.hasNextLine()));
        System.out.println("SC2a nextInt " + go(s("  x y"), q -> q.nextInt()));
        System.out.println("SC2a next(p) " + go(s("a  \n b"), q -> q.next(), q -> q.next("zz")));
        System.out.println("SC2a nextDouble " + go(s("  x"), q -> q.nextDouble()));
        System.out.println("SC2a radix first " + go(s("  x"), q -> q.nextInt(37)));
        // SC6: an empty line has nothing to find, not even an empty match (M05).
        System.out.println("SC6 empty line " + go(s("\nx"), q -> q.findInLine("\\s*"), q -> q.findInLine("x?")));
        System.out.println("SC6 end " + go(s(""), q -> q.findInLine("")) + " || " + go(s("ab"), q -> q.findInLine(".*"), q -> q.findInLine("")));
        // SC7: hasNextLine leaves the line (M08); hasNext() clears; nextLine after hasNextLine (M04, M10).
        System.out.println("SC7 hasNextLine " + go(s("ab\ncd"), q -> q.hasNextLine()));
        System.out.println("SC7 hasNext " + go(s("ab\ncd"), q -> q.hasNext()));
        System.out.println("SC7 hHL " + go(s("ab cd\nef"), q -> q.hasNextLine(), q -> q.hasNext(), q -> q.nextLine()));
        System.out.println("SC7 hL " + go(s("ab cd\nef"), q -> q.hasNextLine(), q -> q.nextLine()));
        System.out.println("SC7 hiL " + go(s("12 cd\nef"), q -> q.hasNextLine(), q -> q.hasNextInt(), q -> q.nextLine()));
        System.out.println("SC7 hfL " + go(s("ab cd\nef"), q -> q.hasNextLine(), q -> q.findInLine("zz"), q -> q.nextLine()));
        System.out.println("SC7 hkL " + go(s("ab cd\nef"), q -> q.hasNextLine(), q -> q.skip("x*"), q -> q.nextLine()));
        System.out.println("SC7 typed yes " + go(s("12 x"), q -> q.hasNextInt()) + " || " + go(s("ab x"), q -> q.hasNext("a(b)")) + " || " + go(s("true x"), q -> q.hasNextBoolean()));
        System.out.println("SC7 typed no " + go(s("ab x"), q -> q.hasNextInt()) + " || " + go(s("ab x"), q -> q.hasNext("zz")));
        System.out.println("SC7 failures " + go(s("ab x"), q -> q.next(), q -> q.nextInt()) + " || " + go(s("ab x"), q -> q.next(), q -> q.skip("zz")) + " || " + go(s("ab"), q -> q.next(), q -> q.nextLine()));
        // SC7a: a token of the right shape that fails to parse stays the match.
        System.out.println("SC7a nextInt " + go(s("99999999999 x"), q -> q.nextInt()) + " || " + go(s("99999999999"), q -> q.hasNextInt()));
        System.out.println("SC7a nextByte " + go(s("200"), q -> q.nextByte()) + " || " + go(s("abc"), q -> q.nextInt()) + " || " + go(s("abc"), q -> q.hasNextInt()));
        System.out.println("SC7a symbols " + go(s("�"), q -> q.hasNextDouble()) + " || " + go(s("∞"), q -> q.nextDouble()) + " || " + go(s("-∞"), q -> q.hasNextDouble(), q -> q.nextFloat()));
        // SC17: a hexadecimal float needs a point (M15).
        System.out.println("SC17 " + go(s("0x1p3 0x1.p3 0x1.8p1"), q -> q.hasNextDouble(), q -> q.next(), q -> q.hasNextDouble(), q -> q.next(), q -> q.nextDouble()));
        // SC24: a typed or pattern hasNext remembers its token across a delimiter change.
        System.out.println("SC24 int " + go(s("12 34"), q -> q.hasNextInt(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(16), d, q -> q.nextInt(16)));
        System.out.println("SC24 kinds " + go(s("12 34"), q -> q.hasNextInt(), d, q -> q.nextLong()) + " || " + go(s("12 34"), q -> q.hasNextDouble(), d, q -> q.nextDouble()) + " || " + go(s("12 34"), q -> q.hasNextFloat(), d, q -> q.nextDouble()));
        System.out.println("SC24 radix " + go(s("12 34"), q -> q.hasNextInt(8), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(10), d, q -> q.nextInt()));
        Pattern p = Pattern.compile("1.");
        System.out.println("SC24 pattern " + go(s("12 34"), q -> q.hasNext("1."), d, q -> q.next("1.")) + " || " + go(s("12 34"), q -> q.hasNext(p), d, q -> q.next(p)) + " || " + go(s("12 34"), q -> q.hasNext(Pattern.compile("1.")), d, q -> q.next(Pattern.compile("1."))) + " || " + go(s("12 34"), q -> q.hasNext("1."), d, q -> q.next()));
        System.out.println("SC24 kept " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNext(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNext("zz"), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNextBoolean(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNext("\\d+"), d, q -> q.nextInt()));
        System.out.println("SC24 dropped " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNextLine(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.findInLine(""), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.reset(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNextInt(2), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.nextBoolean(), d, q -> q.nextInt()));
        Op comma = q -> q.useDelimiter(",") != null;
        System.out.println("SC24 shape " + go(s("12 34"), q -> q.hasNextInt(), comma, q -> q.hasNextInt(), q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), comma, q -> q.hasNextDouble(), q -> q.nextInt()) + " || " + go(s("1234 5"), q -> q.hasNextInt(), q -> q.hasNextByte(), d, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.hasNextByte(2), d, q -> q.nextInt()) + " || " + go(s("1e999 3"), q -> q.hasNextInt(), q -> q.hasNextDouble(), q -> q.hasNextFloat(), d, q -> q.nextInt()));
        System.out.println("SC24 radix change " + go(s("12 34"), q -> q.hasNextInt(16), comma, q -> q.hasNextInt(), q -> q.nextInt(16)) + " || " + go(s("12 34"), q -> q.hasNextInt(), comma, q -> q.hasNextInt(16), q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(16), comma, q -> q.hasNextDouble(), q -> q.nextInt(16)) + " || " + go(s("12 34"), q -> q.hasNextInt(), comma, q -> q.hasNextLong(), q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), q -> q.useRadix(16), q -> q.useRadix(10), d, q -> q.nextInt()));
        Op nines = q -> q.useDelimiter("9") != null;
        System.out.println("SC24 new shape " + go(s("99999999999 x"), q -> q.hasNextDouble(), q -> q.hasNextInt(), nines, q -> q.nextDouble()) + " || " + go(s("99999999999 x"), q -> q.hasNextDouble(), q -> q.hasNextFloat(), nines, q -> q.nextDouble()));
        System.out.println("SC24 pattern dropped " + go(s("12 34"), q -> q.hasNext("1."), q -> q.hasNextBoolean(), d, q -> q.next("1.")) + " || " + go(s("12 34"), q -> q.hasNext("1."), q -> q.hasNext("zz"), d, q -> q.next("1.")) + " || " + go(s("12 34"), q -> q.hasNext("\\d+"), q -> q.hasNextInt(), d, q -> q.next("\\d+")) + " || " + go(s("12 34"), q -> q.hasNext("1."), q -> q.hasNext(), d, q -> q.next("1.")));
        System.out.println("SC24 once " + go(s(" 12 34"), q -> q.hasNextInt(), q -> q.useDelimiter(",") != null, q -> q.nextInt()) + " || " + go(s("12 34"), q -> q.hasNextInt(), d, q -> q.hasNextInt(), q -> q.nextInt()));
        // Stream sources: a delimiter or token pattern that matched only at the end of what was read
        // (requireEnd) is decided again with more input (M01, M07).
        System.out.println("requireEnd token " + go(one("a,b c"), q -> q.useDelimiter(",$| ") != null, q -> q.next(), q -> q.next()) + " || " + go(s("a,b c"), q -> q.useDelimiter(",$| ") != null, q -> q.next(), q -> q.next()));
        System.out.println("requireEnd pattern " + go(one("ab \nc"), q -> q.useDelimiter(" ") != null, q -> q.hasNext("ab(?= $)")) + " || " + go(s("ab \nc"), q -> q.useDelimiter(" ") != null, q -> q.hasNext("ab(?= $)")) + " || " + go(one("ab \n"), q -> q.useDelimiter(" ") != null, q -> q.hasNext("ab(?= $)")));
        System.out.println("requireEnd find " + go(one("ab\nc"), q -> q.findInLine("b$")) + " || " + go(s("ab\nc"), q -> q.findInLine("b$")) + " || " + go(one("ab\nc"), q -> q.findWithinHorizon("b$", 3)) + " || " + go(one("ab\nc"), q -> q.findWithinHorizon("b$", 0)) + " || " + go(one("ab\nc"), q -> q.findWithinHorizon("b(?=\n$)", 2)));
        // Horizons (M06): the bound holds on a stream too, and a horizon past the end is the end.
        System.out.println("horizon " + go(one("aaab"), q -> q.findWithinHorizon("a*b", 3), q -> q.findWithinHorizon("a+", 2), q -> q.findWithinHorizon("a*b", 9)) + " || " + go(s("aaab"), q -> q.findWithinHorizon("a*b", 3), q -> q.findWithinHorizon("a+", 2), q -> q.findWithinHorizon("a*b", 9)));
        // SC21a: constructors check their arguments in a fixed order.
        File missing = new File("no-such-file-for-scanner-edges.txt");
        Path pm = Paths.get("no-such-file-for-scanner-edges.txt");
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        System.out.println("ctor stream " + ex(() -> new Scanner((InputStream) null, "UTF-8")) + " | " + ex(() -> new Scanner((InputStream) null, "bogus")) + " | " + ex(() -> new Scanner(empty, "b@d")) + " | " + ex(() -> new Scanner((InputStream) null)));
        System.out.println("ctor file " + ex(() -> new Scanner(missing, "bogus")) + " | " + ex(() -> new Scanner(missing, "b@d")) + " | " + ex(() -> new Scanner(missing, (String) null)) + " | " + ex(() -> new Scanner((File) null, "bogus")) + " | " + ex(() -> new Scanner((File) null)));
        System.out.println("ctor path " + ex(() -> new Scanner(pm, "bogus")) + " | " + ex(() -> new Scanner(pm, "b@d")) + " | " + ex(() -> new Scanner(pm, (String) null)) + " | " + ex(() -> new Scanner((Path) null, "bogus")) + " | " + ex(() -> new Scanner((Path) null)) + " | " + ex(() -> new Scanner(pm, "UTF-8")).replace("\\", "/"));
        // SC20: a closed scanner says so before looking at its arguments; toString shows it closed.
        Scanner c = s("x");
        c.close();
        System.out.println("closed order " + ex(() -> c.findWithinHorizon("x", -1)) + " | " + ex(() -> c.findWithinHorizon((Pattern) null, 0)) + " | " + ex(() -> c.next((Pattern) null)) + " | " + ex(() -> c.hasNext((Pattern) null)) + " | " + ex(() -> c.findInLine((Pattern) null)) + " | " + ex(() -> c.skip((Pattern) null)) + " | " + ex(() -> c.hasNext((String) null)) + " | " + ex(() -> c.nextInt(37)));
        System.out.println("closed toString " + show(c).contains("[source closed=true]") + " | open " + ex(() -> s("x").findWithinHorizon((Pattern) null, -1)));
        System.out.println("done");
    }
}
