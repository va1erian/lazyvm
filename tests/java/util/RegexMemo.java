package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Searches long enough to switch on the loop memo (LoopMemo), over patterns where remembering a
 * failed state would be wrong: back references, captures that survive a failed path, loops in
 * lookbehind bodies and loops inside loops. Each row lists every match with its groups.
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
        row("(?:(?:(?!x)a)+){2}c", block + block + block + a(300) + "c");
        System.out.println("== a counted loop: the same position fails with one count and succeeds with another");
        for (int n = 16; n <= 24; n += 4) {
            row("(?:a|aa){3}b", a(n) + "c" + a(5) + "b");
            row("(?:a|aa){2,3}(?:b|$)", a(n) + "c" + a(5));
            row("(?:aa?){4}$", a(n) + "c" + a(7));
        }
        System.out.println("== the exact back reference case: the match starts after the c");
        row("(?:aa|(a))*\\1b", a(20) + "c" + a(10) + "ab");
        row("(?:aa|(a)|aaa)*\\1b", a(20) + "c" + a(16) + "ab");
        row("(?:(aa)|a)*\\1b", a(20) + "c" + a(16) + "aab");
        row("(?:aa|(a)){2,}\\1b", a(20) + "c" + a(24) + "ab");
        System.out.println("== a body that must end at one place, after many failed attempts");
        row("(?<=^(?:ab){1,200})x", rep("ab", 150) + "x");
        row("(?<=^(?:ab){3,300})x", rep("ab", 200) + "x");
        System.out.println("== a long run of failed starts first, so that the memo is on for the cases that follow");
        String junk = rep("z", 21000);
        row("(?:a(a)++|b|aa|(a)*+b)*c", junk + "babaaabaabac");
        row("(?:a|ab|(b)++a|(a)++b)*ac", junk + "aaababbabac");
        row("(?:aa|(a))*\\1b", junk + a(12) + "ab");
        row("(?:a|a(?=(a)))*b", junk + a(12) + "x aaab");
        row("(?:(a)|(aa))*\\1\\2x", junk + a(12) + "aaax");
        row("[ab]*(?:ab){2,}x", junk + rep("ab", 3) + "x");
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
