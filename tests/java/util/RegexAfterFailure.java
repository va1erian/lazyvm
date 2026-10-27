package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the matcher's "end of the previous match" is after an attempt fails, which is where \G
 * matches and where the next find() starts. Each row reads it back with \G after a failed
 * matches(), lookingAt() and find() on a set of texts ("-" when the call succeeded). The sweep at
 * the end hashes the same readings for every pair of 21 fragments and all texts up to length 4.
 */
public class RegexAfterFailure {
    static final String[] TEXTS = {"", "a", "b", "ab", "aab", "aabx", "abcx", "abx", "ababx", "ababab", "1,22,333;", "aaax", "abab", "abc",
        "abcab", "aabbc", "abbc", "abd", "xab", "aa", "aaa"};

    static String last(Matcher m) {
        m.usePattern(Pattern.compile("\\G(?s:.?)"));
        return m.find() ? String.valueOf(m.start()) : "n";
    }

    static String reading(Pattern p, String text, int how) {
        Matcher m = p.matcher(text);
        boolean ok = how == 0 ? m.matches() : how == 1 ? m.lookingAt() : m.find();
        return ok ? "-" : last(m);
    }

    static void row(String re) {
        Pattern p = Pattern.compile(re);
        StringBuilder sb = new StringBuilder(re + " :");
        for (int how = 0; how < 3; how++) {
            sb.append(how == 0 ? "" : " |");
            for (String t : TEXTS) {
                sb.append(' ').append(reading(p, t, how));
            }
        }
        System.out.println(sb);
    }

    static final String[] FRAGMENTS = {"a", "ab", "(?:ab)*", "(?:ab)+?", "(?:a|b)*", "(a|bb)+", "a+", "b*?", "b?", "(?:a+b)*", "(?:a(?=b))*",
        "(a)*", "(?:a|ab)*", "(?:ab){1,2}", "[ab]*+", "(?:a*b)+", "(?:ab)?", "(?:a|b)*+", "(?>(?:a|bb)*)", "(?:a|bb)*+", "(?:ab|a)++"};

    public static void main(String[] args) {
        System.out.println("== a repeated class: the end of its run, not moved by backing off");
        for (String re : new String[] {"a*b", "a+b", "a*?b", "(a)*b", "[ab]*c", "ab*c", "a{2,3}b", "(\\n*?){2}a", "(a+)+c", "(?:a+)+c"}) {
            row(re);
        }
        System.out.println("== a loop over a fixed-width body: the end of its last iteration");
        for (String re : new String[] {"(?:ab)*c", "(?:ab)*?c", "(?:ab)*+c", "(?:ab){2}c", "(?:ab){2,3}c", "x(?:ab)*c", "a(?:bc)+d", "(?:a(?=b)b)*c", "(?:(?:ab)+c)+d"}) {
            row(re);
        }
        System.out.println("== a loop written ?, or over alternatives or a variable body, records none of its own");
        for (String re : new String[] {"(?:ab)?c", "(?:a|b)*c", "(a|b)*c", "(?:a|b)+", "(?:a|aa)*b", "(?:ab|a)*c", "(?:ab|c)*d", "(?:a(?:b|c))*d", "(?:a|ab)+c",
            "(?:a+b)*c", "(?:a*b)+c", "\\d+(?:,\\d+)*", "(?:a|b){2}c", "(?:a|b)?c", "(?:x|ab)*c"}) {
            row(re);
        }
        System.out.println("== possessive and atomic: the end of the whole group");
        for (String re : new String[] {"(?:a|b)*+c", "(a|b)*+c", "(?>a|b)*c", "(?:a|bb)*+c", "(?>(?:a|bb)*)c", "(?>(?:ab)?)c", "(?>(?:a+b)*)c", "(?>a+b)c", "(?>a+c)",
            "(?>(?:ab)*c)c", "(?>a*b)"}) {
            row(re);
        }
        System.out.println("== every pair of fragments");
        String[] texts = new String[121];
        int n = 0;
        texts[n++] = "";
        for (int len = 1; len <= 4; len++) {
            int total = 1;
            for (int i = 0; i < len; i++) {
                total *= 3;
            }
            for (int k = 0; k < total; k++) {
                StringBuilder sb = new StringBuilder();
                int x = k;
                for (int i = 0; i < len; i++) {
                    sb.append("abc".charAt(x % 3));
                    x /= 3;
                }
                texts[n++] = sb.toString();
            }
        }
        long hash = 0;
        int patterns = 0;
        for (String x : FRAGMENTS) {
            for (String y : FRAGMENTS) {
                Pattern p = Pattern.compile(x + y + "c");
                StringBuilder sb = new StringBuilder();
                for (String t : texts) {
                    for (int how = 0; how < 3; how++) {
                        sb.append(reading(p, t, how));
                    }
                }
                hash = hash * 31 + sb.toString().hashCode();
                patterns++;
            }
        }
        System.out.println(patterns + " patterns, hash " + hash);
        System.out.println("done");
    }
}
