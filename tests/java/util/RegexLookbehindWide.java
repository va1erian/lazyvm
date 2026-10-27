package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Lookbehind over supplementary characters, second part (see RegexLookbehindPairs). A lookbehind
 * whose pattern text has a surrogate char written raw at or after the start of the lookbehind
 * steps back by whole code points; any other lookbehind steps back by chars. Rows cover repeats,
 * every kind of class, raw characters before and after, regions that split pairs, and the
 * overflow check of the reach.
 */
public class RegexLookbehindWide {
    static final String E = "😀";

    static String esc(String s) {
        StringBuilder o = new StringBuilder();
        for (char c : s.toCharArray()) {
            o.append(c < 128 ? String.valueOf(c) : String.format("<%04x>", (int) c));
        }
        return o.toString();
    }

    static String finds(String p, String t) {
        StringBuilder o = new StringBuilder();
        try {
            Matcher m = Pattern.compile(p).matcher(t);
            while (m.find()) {
                o.append(m.start()).append('-').append(m.end()).append(' ');
            }
        } catch (PatternSyntaxException e) {
            o.append("ERR ").append(e.getDescription());
        }
        return o.toString().trim();
    }

    static void row(String p, String[] texts) {
        StringBuilder sb = new StringBuilder(esc(p) + ":");
        for (String t : texts) {
            sb.append(" [").append(finds(p, t)).append(']');
        }
        System.out.println(sb);
    }

    /** The cases of the review: repeats, a raw character before the lookbehind, classes. */
    static void reviewed() {
        row("(?<=" + E + "{2})A", new String[] {E + E + "A", E + "A"});
        row("(?:" + E + "){2}", new String[] {E + E + "A"});
        row("(?<=[a" + E + "]{2})A", new String[] {E + "a" + E + "aA", "aaA"});
        row(E + "|(?<=\\x{1F600})A", new String[] {E + "A"});
        row("(?:" + E + ")?(?<=\\x{1F600}y)A", new String[] {E + "yA"});
        row("(?<=\\p{So})A|" + E, new String[] {E + "A"});
        row("(?<=x.)A|" + E, new String[] {"x" + E + "A"});
        row("(?<=x" + E + "{2})A", new String[] {"x" + E + E + "A"});
        row("(?<=(?:" + E + "a){2})A", new String[] {E + "a" + E + "aA"});
        row("(?<=(?:" + E + "a){715827883})A", new String[] {"A"});
        row("(?<=x{1073741823}" + E + "{1073741823})A", new String[] {"A"});
        row("(?<=[\\uE000-\\x{1F600}])A|" + E, new String[] {E + "A", "A"});
        row("(?<=[a-z])A|" + E, new String[] {"xA", E + "A"});
        row("(?<=[\\x{1F600}][a-z])A|" + E, new String[] {E + "xA"});
    }

    /** Each kind of class after a raw character, a raw character before, and none at all. */
    static void classes() {
        String[] pieces = {"\\x{10000}", "[\\x{10000}]", "[a\\x{10000}]", "[\\x{FFFF}-\\x{10000}]", "[\\uE000-\\x{10000}]", ".", "(?s).", "[^a]", "\\P{Lu}", "\\W", "\\D",
            "\\S", "\\H", "\\V", "\\p{L}", "\\p{Lu}", "\\p{So}", "\\P{So}", "[^\\x{1F600}]", "[\\p{L}]", "[^\\p{L}]", "\\p{InGothic}", "(?U)\\w", "(?U)\\d", "(?i)a",
            "(?i)\\x{10400}", "\\p{ASCII}", "\\p{IsAlphabetic}", "\\p{Co}", "\\p{Cn}", "\\p{javaLetter}", "\\p{all}", "[\\ud800-\\udfff]", "\\p{Cs}", "[a-z&&[^b]]",
            "[a-z[\\x{10000}]]", "\\p{L}?", "\\p{L}{2}", "a|\\x{10000}", "\\x{10000}|a", "[a-z]", "\\w", "\\s", "\\d", "\\b"};
        int[] chars = {0x10000, 0x10400, 0x10428, 0x1F600, 0x1D7CE, 0xF0000, 0x30000};
        for (String piece : pieces) {
            for (int variant = 0; variant < 4; variant++) {
                StringBuilder sb = new StringBuilder(esc(piece) + " v" + variant + ":");
                for (int c : chars) {
                    String t = "x" + new String(Character.toChars(c)) + "A";
                    String p;
                    if (variant == 0) {
                        p = "(?<=x" + piece + ")A";
                    } else if (variant == 1) {
                        p = "(?<=x" + piece + ")A(?:(?!)" + E + ")?";
                    } else if (variant == 2) {
                        p = E + "|(?<=x" + piece + ")A";
                    } else {
                        p = "y|(?<=x" + piece + ")A|" + E;
                    }
                    sb.append(" [").append(finds(p, t)).append(']');
                }
                System.out.println(sb);
            }
        }
    }

    /** A literal run that ends with a high surrogate does not match before a low surrogate. */
    static void runs() {
        String[] ps = {"ab\\ud83d", "a\\ud83d", "a\\ud83db", "\\ud83d\\ud83d", "a\\ude00", "a\\ude00b", "a" + E, "ab(?:\\ud83d)", "a[\\ud83d]", "\\ud801\\udc00\\ud83d",
            "𐐀\\ud83d"};
        String[] ts = {"a" + E, "ab" + E, "a\ud83d", "a\ud83db", "a\ud83d\ud83d", "a" + E + E, "a\ude00", "a\ude00b"};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (String t : ts) {
                sb.append(" [").append(finds(p, t));
                Matcher m = Pattern.compile(p).matcher(t);
                m.region(0, t.length() - 1).useTransparentBounds(true);
                sb.append(" | ").append(m.find() ? m.start() + "-" + m.end() : "none").append(']');
            }
            System.out.println(sb);
        }
    }

    /** Regions that start or end inside a pair, with and without transparent bounds. */
    static void regions() {
        String t = "b" + E + "a" + E + "by\ud83d";
        String[] ps = {"(?<=\\uDE00)[^" + E + "]", "(?<!\\uDE00)x|" + E, "(?<=[^a])(?:a|\\x{10400})*?", "(?<=\\uDE00)a|" + E, "(?<=.)b|" + E, "(?<=\\x{1F600})a|" + E,
            "(?<=.)a"};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (int lo = 0; lo <= 3; lo++) {
                for (int hi = 4; hi <= t.length(); hi += 2) {
                    for (int tb = 0; tb < 2; tb++) {
                        Matcher m = Pattern.compile(p).matcher(t);
                        m.region(lo, hi).useTransparentBounds(tb == 1);
                        sb.append(' ');
                        while (m.find()) {
                            sb.append(m.start()).append('-').append(m.end()).append(',');
                        }
                    }
                }
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) {
        regions();
        reviewed();
        classes();
        runs();
        System.out.println("done");
    }
}
