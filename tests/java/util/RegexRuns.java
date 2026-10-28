package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches for patterns that begin with a repeated class over long runs of it, as in "\s*,\s*":
 * a failed attempt inside a run makes the later starts of the run pointless (SearchHints), so the
 * search stays linear. Each row prints the matches, the flags, and where the next search starts
 * after a failed one (read with \G), which the skipped starts must not change. Timed: before the
 * shortcut covered patterns like these, a run of 5000 spaces took about 40 s.
 */
public class RegexRuns {
    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static void row(String re, String text) {
        Matcher m = Pattern.compile(re).matcher(text);
        StringBuilder sb = new StringBuilder("#" + re + " len" + text.length() + ":");
        while (m.find()) {
            sb.append(" [").append(m.start()).append(',').append(m.end());
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.start(g)).append(':').append(m.end(g));
            }
            sb.append(']');
        }
        sb.append(" hitEnd=").append(m.hitEnd()).append(" requireEnd=").append(m.requireEnd());
        m.usePattern(Pattern.compile("\\G"));
        sb.append(" then \\G at ").append(m.find() ? String.valueOf(m.start()) : "none");
        System.out.println(sb);
    }

    public static void main(String[] args) {
        String spaces = rep(" ", 5000);
        long start = System.nanoTime();
        row("[ ]*,[ ]*", "y" + spaces);
        row("[ ]*,[ ]*", "y" + spaces + ",   z");
        row("\\s*,\\s*", "a" + spaces + "b , c" + spaces + ",");
        long millis = (System.nanoTime() - start) / 1000000;
        System.out.println(millis < 10000 ? "fast" : "slow " + millis + " ms");
        String some = rep(" ", 300);
        String[] patterns = {
            "[ ]*,[ ]*", "[ ]*?,[ ]*", "[ ]*+,", "[ ]+,[ ]*", "[ ]{2,},", "[ ]*,(?=x)", "[ ]*(,)[ ]*",
            "[ ]*,?[ ]*x", "[ ]*(?:ab)*x", "[ ]*(?:ab)*+x", "[ ]*[ab]+x", "[ ]*(?<= {3})x", "[ ]*\\bx",
            "[ ]*(?=( )|x)x", "[ ]*(?: (?=(b)))*x", "(?:a|b)*,", "(?:a|b)*(?:ab)*x", "[ ]*$",
        };
        for (String re : patterns) {
            row(re, "y" + some + "ab" + some + "abab" + some + ",x");
            row(re, "y" + some + "ab" + some);
        }
        System.out.println("done");
    }
}
