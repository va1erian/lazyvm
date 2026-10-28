package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The second part of RegexMemo: the loop memo over loops in lookarounds and inside loops.
 * RegexMemoStarts has the rest; the parts are split so each stays well under the CI time limit.
 */
public class RegexMemoLoops {
    static String a(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append('a');
        }
        return sb.toString();
    }

    static void row(String re, String text) {
        StringBuilder sb = new StringBuilder("#" + re + " len" + text.length() + ":");
        Matcher m;
        try {
            m = Pattern.compile(re).matcher(text);
        } catch (PatternSyntaxException e) {
            System.out.println(sb + " rejected");
            return;
        }
        while (m.find()) {
            sb.append(" [").append(m.start()).append(',').append(m.end());
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.start(g)).append(':').append(m.end(g));
            }
            sb.append(']');
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        System.out.println("== loops in lookarounds and inside loops");
        for (int n = 14; n <= 22; n += 4) {
            row("(?<=(?:a(?>a?)){0,30}b)c", a(n) + "bc");
            row("(?<=(?:aa){0,30}b)c", a(n) + "bc");
            row("(?<=(?>a|aa){1,30})x", a(n) + "x" + a(n) + "x");
            row("(?<!(?>aa|a){3,30}b)c", a(n) + "bc" + "bc");
            row("(?=(?:a|aa)*b)a", a(n) + "c" + a(3) + "b");
            row("(?:a(?=(?:a|aa)*c)|a)*b", a(n) + "x" + a(3) + "b");
        }
        String block = a(300) + "d";
        row("(?:(?:aa)+){2}c", block + block + block + a(300) + "c");
        row("(?:(?:aa)+){3}c", block + block + block + a(300) + "c");
        System.out.println("done");
    }

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
