package lazy.util;

import java.io.*;
import java.util.*;
import java.util.regex.*;

/**
 * LazyVM's rule for Scanner.match() positions on long input (util-text.md section 4): start()
 * and end() count from the start of the whole input, however much has been read and dropped
 * since. The oracle counts from its own buffer instead once the input passes about 1 KB, so this
 * golden is hand-written. It also checks that what hasNext calls remember (SC24) and the
 * hasNextLine position (SC7) survive text being dropped from the buffer.
 */
public class ScannerPositions {
    static String m(Scanner s) {
        try {
            MatchResult r = s.match();
            return r.group() + "@" + r.start() + "-" + r.end();
        } catch (IllegalStateException e) {
            return "no match";
        }
    }

    static String lines(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("a\n");
        return b.toString();
    }

    public static void main(String[] args) {
        for (int k = 0; k < 2; k++) {
            String text = lines(10000) + "b 12 c\nd";
            Scanner s = k == 0 ? new Scanner(text) : new Scanner(new StringReader(text));
            String src = k == 0 ? "string" : "reader";
            for (int i = 0; i < 10000; i++) s.nextLine();
            System.out.println(src + " next " + s.next() + " " + m(s));
            System.out.println(src + " hasNextInt " + s.hasNextInt() + " " + m(s));
            s.useDelimiter("");
            System.out.println(src + " remembered nextInt " + s.nextInt() + " " + m(s));
            s.reset();
            System.out.println(src + " hasNextLine " + s.hasNextLine() + " " + m(s).replace("\n", "\\n"));
            System.out.println(src + " hasNext " + s.hasNext() + " " + m(s));
            System.out.println(src + " nextLine [" + s.nextLine() + "] " + m(s));
            System.out.println(src + " findInLine " + s.findInLine("d") + " " + m(s));
        }
        // A token far past the first kilobyte, from a reader handing out 3 chars at a time.
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 3000; i++) b.append(i % 10).append(' ');
        Scanner s = new Scanner(new Reader() {
            final String t = b.toString();
            int p;
            public int read(char[] c, int off, int len) {
                if (p >= t.length()) return -1;
                int n = Math.min(3, Math.min(len, t.length() - p));
                t.getChars(p, p + n, c, off);
                p += n;
                return n;
            }
            public void close() {}
        });
        long sum = 0;
        int count = 0;
        String lastMatch = "";
        while (s.hasNextInt()) {
            sum += s.nextInt();
            count++;
            lastMatch = m(s);
        }
        System.out.println("chunked ints " + count + " sum " + sum + " last " + lastMatch);
        System.out.println("done");
    }
}
