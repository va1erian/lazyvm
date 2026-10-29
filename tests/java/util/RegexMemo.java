package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Searches long enough to switch on the loop memo (LoopMemo), over patterns where remembering a
 * failed state would be wrong: back references, captures that survive a failed path and counted
 * loops. Each row lists every match with its groups. RegexMemoLoops, RegexMemoStarts and
 * RegexMemoAround have the rest; they are split so each stays well under the CI time limit.
 */
public class RegexMemo {
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
        System.out.println("== back references, also after the loop");
        for (int n = 14; n <= 22; n += 4) {
            row("(?:aa|(a))*\\1b", a(n) + "c" + a(n / 2) + "ab");
            row("(?:aa|(a)|aaa)*\\1b", a(n) + "ab");
            row("(?:(aa)|a)*\\1b", a(n) + "aab");
            row("(?:aa|(a)){2,}\\1b", a(n) + "ab");
            row("(?:(a)|(aa)|(b))*\\2c", a(n) + "c" + a(n) + "aac");
            row("(?:(a)|(aa))*\\1\\2x", a(n) + "aaax");
            row("(?:(a)|(a)a)*b\\2", a(n) + "ba" + a(n) + "b");
        }
        System.out.println("== captures that a failed path leaves behind");
        for (int n = 14; n <= 18; n += 4) {
            row("(?:a|a(?=(a)))*b", a(n) + "x aaab");
            row("(?:a|(a)(?=(a))a)*b", a(n) + "x aaab");
            row("(?:a|(?=(a))aa)*b", a(n) + "x aaab");
            row("(?:a|(?!(b))aa)*b", a(n) + "x aaab");
            row("(?:(?<=(a))a|aa)*b", a(n) + "x aaab");
            row("(?:a|(?>(a))a)*b", a(n) + "x aaab");
            row("(?:a|(a)++a)*b", a(n) + "x aaab");
        }
        System.out.println("== a counted loop: the same position fails with one count and succeeds with another");
        for (int n = 16; n <= 24; n += 4) {
            row("(?:a|aa){3}b", a(n) + "c" + a(5) + "b");
            row("(?:a|aa){2,3}(?:b|$)", a(n) + "c" + a(5));
            row("(?:aa?){4}$", a(n) + "c" + a(7));
        }
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
