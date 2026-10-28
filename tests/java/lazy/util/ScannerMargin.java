package lazy.util;

import java.io.*;
import java.util.*;

/**
 * LazyVM's rule for long input (util-text.md SC2b and section 4): when the Scanner drops text it
 * has passed, it keeps a margin of it, so lookbehind still sees the text just before the
 * position, and `^` and `\A` still match only at the true start of the input. Each case is
 * checked against the answer computed directly from the whole string, from a String source and
 * from a Reader with short reads, far past the 8 KB where text starts being dropped. The oracle
 * has its own artifacts at its 1 KB buffer edges here, so this golden is hand-written.
 */
public class ScannerMargin {
    /** Hands out its text 7 chars per read, never saying it is ready. */
    static Reader seven(String t) {
        return new Reader() {
            int p;
            public int read(char[] c, int off, int len) {
                if (p >= t.length()) return -1;
                int n = Math.min(7, Math.min(len, t.length() - p));
                t.getChars(p, p + n, c, off);
                p += n;
                return n;
            }
            public void close() {}
        };
    }

    static List<String> tokens(Scanner s) {
        List<String> r = new ArrayList<>();
        while (s.hasNext()) r.add(s.next());
        return r;
    }

    static void check(String name, List<String> expected, Scanner fromString, Scanner fromReader) {
        List<String> a = tokens(fromString);
        List<String> b = tokens(fromReader);
        System.out.println(name + ": " + expected.size() + " tokens, string " + (a.equals(expected) ? "agrees" : "DIFFERS " + a.size())
            + ", reader " + (b.equals(expected) ? "agrees" : "DIFFERS " + b.size()));
    }

    public static void main(String[] args) {
        // A lookbehind delimiter: split at a comma only after a digit.
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5000; i++) b.append(i).append(i % 7 == 0 ? ",x," : ",");
        String text = b.toString();
        List<String> numbers = Arrays.asList(text.split("(?<=\\d),"));
        check("lookbehind (?<=\\d),", numbers, new Scanner(text).useDelimiter("(?<=\\d),"), new Scanner(seven(text)).useDelimiter("(?<=\\d),"));
        // A lookbehind that sees the previous token: split before c only after b.
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < 10000; i++) c.append("abc");
        List<String> abs = new ArrayList<>(Arrays.asList(c.toString().split("(?<=b)c")));
        abs.removeIf(String::isEmpty);
        check("lookbehind (?<=b)c", abs, new Scanner(c.toString()).useDelimiter("(?<=b)c"), new Scanner(seven(c.toString())).useDelimiter("(?<=b)c"));
        // ^ without MULTILINE matches only at the start of the input, however far the scanner is.
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 6000; i++) lines.append("ab\n");
        for (int k = 0; k < 2; k++) {
            Scanner s = k == 0 ? new Scanner(lines.toString()) : new Scanner(seven(lines.toString()));
            int found = 0;
            int at = -1;
            for (int i = 0; i < 6000; i++) {
                if (s.findInLine("^a") != null) {
                    found++;
                    at = i;
                }
                s.nextLine();
            }
            Scanner t = k == 0 ? new Scanner(lines.toString()) : new Scanner(seven(lines.toString()));
            int multi = 0;
            for (int i = 0; i < 6000; i++) {
                if (t.findInLine("(?m)^a") != null) multi++;
                t.nextLine();
            }
            System.out.println((k == 0 ? "string" : "reader") + " ^a found " + found + " at line " + at + ", (?m)^a found " + multi);
        }
        System.out.println("done");
    }
}
