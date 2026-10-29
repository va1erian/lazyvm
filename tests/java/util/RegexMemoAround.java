package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The loop memo (LoopMemo) over patterns with lookarounds, atomic groups and possessive repeats
 * but no capture that outlives a failed path: one timed search that is exponential without it,
 * then searches long enough to switch it on (a run of 21000 failing starts first) where a wrong
 * skip would show in the matches or their groups. RegexMemo, RegexMemoLoops and
 * RegexMemoStarts have the rest.
 */
public class RegexMemoAround {
    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    static void row(String re, String text, int skip) {
        StringBuilder sb = new StringBuilder("#" + re + " len" + text.length() + ":");
        Matcher m;
        try {
            m = Pattern.compile(re).matcher(text);
        } catch (PatternSyntaxException e) {
            System.out.println(sb + " rejected");
            return;
        }
        while (m.find()) {
            sb.append(" [").append(m.start() - skip).append(',').append(m.end() - skip);
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.start(g) < 0 ? -1 : m.start(g) - skip).append(':')
                    .append(m.end(g) < 0 ? -1 : m.end(g) - skip);
            }
            sb.append(']');
        }
        sb.append(" hitEnd=").append(m.hitEnd()).append(" requireEnd=").append(m.requireEnd());
        System.out.println(sb);
    }

    public static void main(String[] args) {
        System.out.println("== exponential without the memo (the oracle needs seconds, it doubles with each a)");
        long start = System.nanoTime();
        row("(?:a|a*)*b(?=c)", rep("a", 20), 0);
        row("(?:a|a*)*b(?=c)", rep("a", 16) + "b", 0);
        long millis = (System.nanoTime() - start) / 1000000;
        System.out.println(millis < 30000 ? "fast" : "slow " + millis + " ms");
        System.out.println("== a greedy loop whose body captures, so it is not a flat loop (the oracle needs seconds)");
        start = System.nanoTime();
        row("(?:(a)|aa)*c", rep("a", 40), 0);
        millis = (System.nanoTime() - start) / 1000000;
        System.out.println(millis < 30000 ? "fast" : "slow " + millis + " ms");
        System.out.println("== after 21000 failing starts, so the memo is on");
        String junk = rep("z", 21000);
        String[] patterns = {
            "(?:a|a*)*b(?=c)",
            "(?:a|a*)*b(?!c)",
            "(a)(?:a|a*)*(b)(?=c)",
            "(?:a(?=a)|a)*b",
            "(?:a(?!b)|aa)*b",
            "(?:(?<=a)a|a)*b",
            "(?:(?<!b)a|aa)*b(?<=ab)",
            "(?:(?>a|aa)|a)*b",
            "(?:a|(?>a*))*b",
            "(?:a|a*+)*b",
            "(?:aa|a)*+b",
            "(?:a|aa)*(?>b)c",
            "((?:a|aa)*)(b)(?=c)",
            "(?:a|aa)*(?=(?:a|aa)*b)a*b",
        };
        for (String re : patterns) {
            row(re, junk + rep("a", 12) + "bd" + rep("a", 7) + "bc" + "aab", junk.length());
        }
        System.out.println("== a failed matches() long enough for the memo, then find() from where it left off");
        String text = "baaaaaababacacacabacaaaaabaaacaababaaaacbb";
        afterFailure("(?:[ab]+?.)*?c", text);
        afterFailure("(?:[ab]+?.)*?(?=a)?c", text);
        afterFailure("(?:[ab]+?.)*?(?=c)c", text);
        afterFailure("(?:[ab]+?.)*?(?>c)", text);
        afterFailure("[ ]*((?:(?>[ab]?.)|.?+[ab]{0,2}?){2,}|[ab]*){2,}c++", "aaaAAaacba");
        System.out.println("done");
    }

    /** matches(), then find() and a \G search, which start where the failed matches() left off. */
    static void afterFailure(String re, String text) {
        Matcher m = Pattern.compile(re).matcher(text);
        StringBuilder sb = new StringBuilder("#" + re + ": matches " + m.matches());
        sb.append(", find ").append(m.find() ? m.start() + "," + m.end() : "no");
        m.reset();
        m.matches();
        m.usePattern(Pattern.compile("\\G"));
        sb.append(", then \\G at ").append(m.find() ? String.valueOf(m.start()) : "none");
        System.out.println(sb);
    }
}
