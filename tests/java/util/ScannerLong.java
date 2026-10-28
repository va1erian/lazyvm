package util;

import java.io.*;
import java.util.*;

/**
 * Scanner on long tokens, lines, delimiter runs, skips and failed searches (200,000 chars for the
 * token, line and skip, from a String and from a Reader). It pins the results; that each case
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
