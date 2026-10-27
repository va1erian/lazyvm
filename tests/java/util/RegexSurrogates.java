package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches over surrogate pairs. A pattern that holds a supplementary character, or negates a
 * general category or block (\P{Lu}), makes a failed search attempt at a high surrogate skip the
 * low half of its pair; an explicit start inside the pair, and every other pattern, do not.
 */
public class RegexSurrogates {
    static final String PAIR = "𝐀"; // U+1D400, an upper case letter

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c < 127 && c >= 32 ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    static String finds(String re, String text) {
        StringBuilder sb = new StringBuilder("[");
        Matcher m = Pattern.compile(re).matcher(text);
        while (m.find()) {
            sb.append(m.start()).append('-').append(m.end()).append(' ');
        }
        return sb.append(']').toString();
    }

    static void row(String re, String... texts) {
        StringBuilder sb = new StringBuilder(esc(re) + " :");
        for (String t : texts) {
            sb.append(' ').append(finds(re, t));
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        String[] texts = {PAIR, "a" + PAIR, PAIR + "b", "ab" + PAIR, PAIR + PAIR};
        System.out.println("== negated category or block: the low half is skipped after a failed attempt");
        String[] skipping = {"\\P{Lu}", "\\P{L}", "\\P{Cs}", "\\P{Cn}", "\\P{InMathematicalAlphanumericSymbols}", "\\P{IsLu}", "\\P{gc=Lu}", "\\P{LD}",
            "[\\P{Lu}]", "[^\\P{L}]", "[a-z\\P{Lu}]", "[\\p{L}&&\\P{Lu}]", "(?i)\\P{Lu}", "\\P{Lu}+", "(\\P{Lu})", "(?=\\P{Lu})",
            "\\P{Lu}*", "(?!\\A)\\P{Lu}*", "(?!\\A)(?:\\P{Sm}|b)"};
        for (String re : skipping) {
            row(re, texts);
        }
        System.out.println("== other patterns see the low half");
        String[] seeing = {"\\p{Cs}", "\\P{all}", "[^\\p{Lu}]", "[^a-z]", "\\P{IsAlphabetic}", "\\P{javaLetter}", "\\P{IsLatin}",
            "\\P{Alpha}", "\\W", "\\D", ".", "(?s).", "\\x{DC00}", "\\p{C}", "(?!\\A)\\P{IsAlphabetic}*", "(?!\\A)[^\\p{Lu}]*"};
        for (String re : seeing) {
            row(re, texts);
        }
        System.out.println("== a supplementary character written in the pattern, anywhere, has the same effect");
        String b = "𝐁";
        String[] literal = {b, "[" + b + "]", "(?x)#" + b + "\n", "(?!\\A)(?:" + b + "(?!)|)", "(?!\\A)(?:\\x{1D401}(?!)|)",
            "(?!\\A)(?:(?x)#" + b + "\n(?!)|)", "(?!\\A)(?:[" + b + "]|)", "(?!\\A)(?:\\Q" + b + "\\E(?!)|)",
            "(?!\\A)(?:\\ud835\\udc01(?!)|)", "(?!\\A)(?:[\\ud835\\udc01]|)"};
        for (String re : literal) {
            row(re, texts);
        }
        System.out.println("== replaceAll and split");
        System.out.println(esc(PAIR.replaceAll("\\P{Lu}", "x")) + " " + esc(("a" + PAIR + "b").replaceAll("\\P{Lu}", "x")));
        System.out.println(esc(PAIR.replaceAll("[^\\p{Lu}]", "x")) + " " + esc(PAIR.replaceAll("\\P{IsAlphabetic}", "x")));
        System.out.println(("a" + PAIR + "b").split("\\P{Lu}").length + " " + ("a" + PAIR + "b").split("\\P{IsAlphabetic}").length);
        System.out.println("== an explicit start inside the pair is not skipped");
        for (String re : new String[] {"\\P{Lu}", "\\P{L}", "(?i)\\P{Lu}", "[\\P{Lu}]", "\\p{Cs}", "\\P{InMathematicalAlphanumericSymbols}"}) {
            Matcher m = Pattern.compile(re).matcher(PAIR);
            String viaFind = m.find(1) ? m.start() + "-" + m.end() : "none";
            m = Pattern.compile(re).matcher(PAIR).region(1, 2);
            String viaRegion = m.lookingAt() ? "yes" : "no";
            m = Pattern.compile(re).matcher(PAIR);
            String viaFindZero = m.find(0) ? m.start() + "-" + m.end() : "none";
            System.out.println(esc(re) + " : find(1) " + viaFind + ", region(1,2) " + viaRegion + ", find(0) " + viaFindZero);
        }
        System.out.println("done");
    }
}
