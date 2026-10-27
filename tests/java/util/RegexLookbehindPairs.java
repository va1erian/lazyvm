package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lookbehind over supplementary characters. How far back a lookbehind looks is counted in chars of
 * the pattern: a character above U+FFFF written as \x{1F600} counts one, unless the pattern has a
 * surrogate char written raw anywhere, when it counts two (and a raw pair counts two). The rows
 * list every match of (?<=body)A, plus a suffix, in a set of texts.
 */
public class RegexLookbehindPairs {
    static String finds(String re, String text) {
        StringBuilder sb = new StringBuilder("[");
        Matcher m = Pattern.compile(re).matcher(text);
        while (m.find()) {
            sb.append(m.start()).append('-').append(m.end()).append(' ');
        }
        return sb.append(']').toString();
    }

    public static void main(String[] args) {
        String pair = "😀";
        String x = "\\x{1F600}";
        String[] bodies = {pair, "[" + pair + "]", "[a" + pair + "]", "[" + pair + "a]", pair + "{1,2}", pair + "+", pair + "*", "(?:" + pair + ")", "(" + pair + ")",
            pair + "|y", "y|" + pair, pair + pair, "a" + pair, pair + "a", "[a-" + pair + "]", "[\\x{10000}-" + pair + "]", "\\Q" + pair + "\\E", x + "a", "a" + x,
            "[a" + x + "]", "[" + x + "-\\x{1F700}]", "(?:" + x + ")", "(" + x + "|y)", x + "{1,2}", x + "*", x + "+", x + x, x + "y", "[" + x + "]", "(" + x + ")", x,
            "[" + x + "b]", "\\ud83d\\ude00", "[\\ud83d\\ude00]", ".", "[^a]", "(?s).", "\\W", "\\P{Lu}"};
        String[] suffixes = {"", "(?:|" + pair + ")", "(?:|" + x + ")", "(?:\\P{Lu}|)"};
        String[] texts = {pair + "A", "x" + pair + "yA", pair + pair + "A", "a" + pair + "A", pair + "aA", "aA" + pair + "A", pair + "aaA"};
        for (String body : bodies) {
            StringBuilder sb = new StringBuilder();
            for (char c : body.toCharArray()) {
                sb.append(c < 127 && c > 31 ? String.valueOf(c) : String.format("<%04x>", (int) c));
            }
            System.out.println("== " + sb);
            for (String suffix : suffixes) {
                StringBuilder row = new StringBuilder();
                for (String text : texts) {
                    row.append(finds("(?<=" + body + ")A" + suffix, text)).append(' ');
                }
                System.out.println(row);
            }
        }
        System.out.println("done");
    }
}
