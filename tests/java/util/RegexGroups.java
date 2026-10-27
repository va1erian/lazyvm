package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Capturing, named, nested and atomic groups; back references; lookahead and lookbehind. */
public class RegexGroups {
    static String show(String s) {
        return s == null ? "null" : "'" + s.replace("\n", "\\n") + "'";
    }

    static void all(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        StringBuilder sb = new StringBuilder(re + " in " + show(in) + ":");
        while (m.find()) {
            sb.append(" [").append(m.start()).append(',').append(m.end()).append(')');
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(show(m.group(g))).append('@').append(m.start(g));
            }
        }
        System.out.println(sb);
    }

    static void full(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        StringBuilder sb = new StringBuilder(re + " vs " + show(in) + ": " + m.matches());
        if (m.hitEnd() || m.requireEnd()) {
            sb.append(" (end)");
        }
        if (m.matches()) {
            for (int g = 0; g <= m.groupCount(); g++) {
                sb.append(' ').append(show(m.group(g)));
            }
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        all("(a)(b)(c)", "abcabc");
        all("(a(b(c)))", "abc");
        all("(a)|(b)", "ab");
        all("(a)?(b)", "b ab");
        all("(?:a)(b)", "ab");
        all("((a)|(b))+", "abab");
        all("(a*)+", "aab");
        all("(a*)*", "aab");
        all("(a|b)*", "abba");
        all("(?<x>a)(?<y>b)?", "a ab");
        all("(\\w)\\1", "hello bookkeeper");
        all("(?<c>\\w)\\k<c>", "hello bookkeeper");
        all("(a)\\1+", "aaa a aa");
        all("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)\\10", "abcdefghijj");
        all("(a)\\10", "aa0");
        all("(a)\\2", "aa");
        all("\\1(a)", "aa");
        all("(a)?\\1b", "b ab aab");
        all("(?i)(a)\\1", "aA Aa AA");
        all("(a)\\1", "aA");
        all("(?<n>[a-z]+) \\k<n>", "abc abc abd abd");
        all("<(\\w+)>.*?</\\1>", "<b>x</b><i>y</i><b>z</i>");
        all("(a+)+b", "aaab");
        all("(?>a+)b", "aaab");
        all("(?>a+)a", "aaa");
        all("(?>a|ab)c", "abc");
        all("(?:a|ab)c", "abc");
        all("a++a", "aaa");
        all("(?>(a)|b)\\1", "aa");
        full("(a|ab)(c|bcd)(d*)", "abcd");
        full("(a|ab)(c|bcd)", "abcd");
        full("(a)|b", "b");
        full("((a)|b)+", "ab");
        full("(?:(a)|(b))+", "ab");
        full("(a*)(a*)", "aaa");
        full("(a*?)(a*)", "aaa");
        full("(.*)(\\d+)", "abc123");
        full("(.*?)(\\d+)", "abc123");
        full("(.*+)(\\d+)", "abc123");
        // lookahead and lookbehind
        all("a(?=b)", "ab ac a");
        all("a(?!b)", "ab ac a");
        all("(?<=a)b", "ab cb b");
        all("(?<!a)b", "ab cb b");
        all("(?<=ab|c)d", "abd cd bd");
        all("(?<=a+)b", "aab b");
        all("(?<=a*)b", "aab b");
        all("(?<=\\d{3})x", "123x 12x");
        all("(?<=(a))b", "ab");
        all("(?=(a))a", "aa");
        all("(?<!(a))b", "cb ab");
        all("\\w+(?=,)", "ab,cd,ef");
        all("(?<=,)\\w+", "ab,cd,ef");
        all("(?<!\\d)\\d{2}(?!\\d)", "12 123 45 6");
        all("(?=.*\\d)(?=.*[a-z])\\w{6,}", "abc123 abcdef 123456");
        all("(?=a)", "ba");
        all("(?!a)", "ba");
        all("(?<=a)", "ba");
        all("(?<!a)", "ba");
        all("$(?<=a)", "a");
        all("(?<=^)a", "a");
        all("(?<=\\b)a", "a ba");
        all("(?<=ab)(?<=b)c", "abc");
        all("(?=a)*b", "ab b");
        full("a(?=b)b", "ab");
        full("a(?!b)", "a");
        full("(?<=a)", "");
        // inline flags and non-capturing groups
        all("(?i)abc", "ABC aBc");
        all("a(?i)bc", "aBC ABC");
        all("a(?i:b)c", "aBc aBC");
        all("(?i)a(?-i)b", "Ab AB");
        all("(a(?i)b)c", "aBc aBC");
        all("(?i:a)b|c", "Ab C");
        all("(?:ab)+", "ababab");
        all("(?:ab){2}", "ababab");
        all("(?s:.)(.)", "\na");
        // captures that outlive a failed branch
        all("(?:(a)|b)*", "ab");
        all("(?:(a)|b)+\\1", "aba");
        all("(a)?+b", "ab");
        all("(?<=(a))*b", "ab");
        // a possessive group keeps its capture when the rest of the match fails after it
        all("(?<=(a)?+)", "aaaab_ab");
        all("(?<!(a)?+b)", "aaaab_ab");
        all("(?:(a)?+b)*c|x", "abab");
        all("(a)*+b|x", "aax");
        all("(?:a)(?>(b))c|b", "abd abc");
        // a group set by an iteration that is later given up is restored (a loop over a flat body)
        all("(ab|c)*c", "abc");
        all("(ab|c)*c", "abcc");
        all("(?:(a)|b)*c", "abbc");
        all("(a|b)*?b", "aab");
        // a group repeated inside a loop that is not flat keeps the last iteration of the inner repeat
        all("(?:(a)+b)*", "aab");
        all("(?:(a)+?b)*c", "aabc");
        all("(?:(a|ab)+c)*", "abcac");
        System.out.println("done");
    }
}
