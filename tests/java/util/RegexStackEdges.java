package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edges of the explicit backtracking stack, the loop memo and the search shortcuts, each row
 * aimed at one way to get them wrong that no other test catches: a capture kept from a failed
 * lookahead that must not let a search skip starts, the last start a search makes, a run that
 * holds a surrogate pair, a lookbehind that widens the bounds, a group restored when the match
 * backs into its body, a backtrack into every frame around the points where the stack grows, and
 * memo states that fail without recording an event, differ only by their count, or sit inside a
 * lookbehind.
 */
public class RegexStackEdges {
    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** Every find() with its groups, the flags, and where the next search starts (read with \G). */
    static String finds(Matcher m, int skip) {
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(" [").append(m.start() - skip).append(',').append(m.end() - skip);
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.start(g) < 0 ? -1 : m.start(g) - skip).append(':')
                    .append(m.end(g) < 0 ? -1 : m.end(g) - skip);
            }
            sb.append(']');
        }
        sb.append(m.hitEnd() ? " H" : " h").append(m.requireEnd() ? "R" : "r");
        m.usePattern(Pattern.compile("\\G"));
        sb.append(" G").append(m.find() ? String.valueOf(m.start() - skip) : "-");
        return sb.toString();
    }

    static void row(String re, String text, int skip) {
        System.out.println("#" + escape(re) + " len" + text.length() + ":" + finds(Pattern.compile(re).matcher(text), skip));
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 126) {
                String hex = Integer.toHexString(c);
                sb.append("\\u");
                for (int k = hex.length(); k < 4; k++) {
                    sb.append('0');
                }
                sb.append(hex);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("== a capture that a failed lookahead keeps: no start of the run may be skipped");
        row("[ ]*(?:(?=(   ))x)?(?:y|(?<=z)b)", "    zb", 0);
        row("[ ]*(?:(?=(   ))x)?(?:y|(?<=z)b)", "      zb", 0);
        System.out.println("== the last start the shortest match allows, inside a run");
        row("[^a]*(\\w((?=b{1,3}\\w\\B{0,2}){0,2}?(?:\\w{1,3}?ba{0,2}?)\\s{2,}+)\\B)", "bbAbbb", 0);
        row("[a-c]{2,}((?i:($${2}+$*|\\b\\W+\\x61)|(b?\\s[ab&&[^b]]){1,3})?)(?:\\x61{1,3}|\\R\\da?){2,}", "acaca", 0);
        System.out.println("== a run that holds surrogate pairs: a start at a low half reads it differently");
        row("\\p{So}*\\uDE00", "😀😀😀😀", 0);
        row("\\p{So}+\\uDE00", "x😀😀😀😀", 0);
        System.out.println("== a lookbehind with transparent bounds, then an anchor on the region");
        for (String re : new String[] {"(?<=a)^b", "(?<=a)\\Bb", "(?<=aa)(?<!a)b|(?<=a)^b"}) {
            Matcher m = Pattern.compile(re).matcher("aab");
            m.region(2, 3);
            m.useTransparentBounds(true);
            System.out.println("#" + re + " region 2,3 transparent:" + finds(m, 0));
        }
        System.out.println("== a group restored when the match backs into its body");
        row("(?:(a|ab)b?)*z", "abbz", 0);
        row("(?:(a|ab)(c|bc)?)*z", "abcbcz", 0);
        System.out.println("== backtracking into frame k, for every k around the points where the stack grows");
        StringBuilder ends = new StringBuilder();
        for (int k = 0; k <= 130; k++) {
            // The first k+1 alternations take "a"; the next one fails, so the match backs into
            // frame k, takes "ab" there and goes on.
            Matcher m = Pattern.compile(rep("(?:a|ab)", k + 3) + "x").matcher(rep("a", k) + "ab" + "aa" + "x");
            ends.append(m.find() ? " " + m.end() : " -");
        }
        System.out.println("#(?:a|ab){k+3 written out}x on a{k}abaax, k = 0..130:" + ends);
        System.out.println("== after 21000 failing starts, so the memo is on");
        String junk = rep("z", 21000);
        row("a*(?:b|bb)*c", junk + "aabbb", 21000);
        row("(?:aa?)*(?:b|bb)*c", junk + "aabbb", 21000);
        row("x?(?:b|bb)*c", junk + "xbbbxbb", 21000);
        row("(?:a|aa){1,3}b", junk + "aaaaab", 21000);
        row("(?:a|aa){2,4}b", junk + "aaaaaaab", 21000);
        String xaa = rep("xaa", 700);
        row("(?<=x(?:aa){0,30}b)c", xaa + "xaabc", xaa.length());
        String spaces = rep(" ", 21000);
        row("(?su)(?:\\w{0,2}){0,2}b", spaces + "\na \nc aba,,", 21000);
        row("[^ ]{1,3}(?:\\Z)*?c{2}", spaces + "\uDE00b,bbAaabA", 21000);
        row("(?s)((?i:ab*?a)*\\B*?)a\\x{1F600}", spaces + "a,abca,a\uDE00aA", 21000);
        row("(?:\\w{0,2}){0,2}b", spaces + "a aba,, ab", 21000);
        System.out.println("done");
    }
}
