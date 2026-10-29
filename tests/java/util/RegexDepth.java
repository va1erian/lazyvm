package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loops over bodies that are not flat (a group, a back reference or a lookaround inside), run for
 * 100000 iterations: matched, backed out of and failed, with every group and flag printed. The
 * shapes are ones the oracle runs without a StackOverflowError at its default stack size.
 */
public class RegexDepth {
    static final int N = 100000;

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** Runs one of find, matches or lookingAt and prints the bounds, the groups and the flags. */
    static void row(String how, String re, String text) {
        Matcher m = Pattern.compile(re).matcher(text);
        boolean found;
        try {
            found = how.equals("matches") ? m.matches() : how.equals("lookingAt") ? m.lookingAt() : m.find();
        } catch (StackOverflowError e) {
            System.out.println(how + " " + re + " len" + text.length() + ": StackOverflowError");
            return;
        }
        StringBuilder sb = new StringBuilder(how + " " + re + " len" + text.length() + ": " + found);
        if (found) {
            sb.append(" [").append(m.start()).append(',').append(m.end()).append(']');
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.start(g)).append(':').append(m.end(g));
            }
        }
        sb.append(" hitEnd=").append(m.hitEnd()).append(" requireEnd=").append(m.requireEnd());
        System.out.println(sb);
    }

    public static void main(String[] args) {
        String ab = rep("ab", N);
        String a = rep("a", N);
        System.out.println("== a group in the body");
        row("find", "(?:(a)b)*", ab);
        row("matches", "(?:(a)b)*", ab);
        row("find", "((a)b)*", ab);
        row("find", "(?:a(b))*", ab);
        row("find", "(?:(a)b)*?c", ab + "c");
        row("find", "(?:(a)b)*?$", ab);
        row("find", "(?:(a)b)*+", ab);
        row("matches", "(?:(a)b){100000}", ab);
        row("lookingAt", "(?:(a)b)*c", ab);
        System.out.println("== a back reference in the body");
        row("find", "(a)\\1*", a + "a");
        row("find", "(a)\\1*?$", a + "a");
        row("lookingAt", "(a)\\1*b", a + "a");
        System.out.println("== a lookaround in the body");
        row("find", "(?:(?=a)a)*", a);
        row("find", "(?:(?=a)a)*b", a + "b");
        row("lookingAt", "(?:(?=a)a)*b", a);
        row("find", "(?:(?!b)(a))*c", a + "c");
        row("find", "(?:(?=(a))a)*", a);
        System.out.println("done");
    }
}
