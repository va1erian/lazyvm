package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Edges of lookbehind and of literal runs next to surrogates: a reach that wrapped negative, a
 * body with fewer code points behind it than its minimum (seen only through captures), the region
 * restored after a transparent lookbehind, a run in a counted loop, and the overflow check of '?'
 * on an atomic group.
 */
public class RegexLookbehindEdges {
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
                o.append(m.start()).append('-').append(m.end());
                for (int g = 1; g <= m.groupCount(); g++) {
                    o.append('g').append(m.start(g)).append(':').append(m.end(g));
                }
                o.append(' ');
            }
        } catch (PatternSyntaxException e) {
            o.append("ERR ").append(e.getDescription()).append(" @").append(e.getIndex());
        }
        return o.toString().trim();
    }

    static void rows(String[] ps, String[] ts) {
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (String t : ts) {
                sb.append(" [").append(finds(p, t)).append(']');
            }
            System.out.println(sb);
        }
    }

    /** A reach that wrapped negative, in both ways of stepping back. */
    static void wrapped() {
        String[] bodies = {"a*b?", "(y)?b*", "c(y)?b*", "a*", "a*b?c?", "b*y?", "(c)?b*(y)?", "x*(a|b)?", "c(?:y|)?b*"};
        String[] ts = {"ab", "xcbbca", "cx", "cbx", "cbbx", "aab", ""};
        for (String raw : new String[] {"", "|" + E}) {
            String[] ps = new String[bodies.length * 2];
            for (int k = 0; k < bodies.length; k++) {
                ps[2 * k] = "(?<=" + bodies[k] + ")\\w" + raw;
                ps[2 * k + 1] = "(?<=" + bodies[k] + ")x" + raw;
            }
            rows(ps, ts);
        }
    }

    /** A wrapped reach allows as many steps as chars remain in the text, whatever the region. */
    static void wrappedRegions() {
        String t = "ppcbbbbxzz" + E + "zz";
        String[] ps = {"(?<=c(y)?b*)x|" + E, "(?<=b*\\S)x|" + E, "(?<=cc(y)?b*)\\w|" + E};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (int hi = 8; hi <= t.length(); hi += 2) {
                for (int tb = 0; tb < 2; tb++) {
                    Matcher m = Pattern.compile(p).matcher(t);
                    m.region(0, hi).useTransparentBounds(tb == 1);
                    sb.append(' ');
                    while (m.find()) {
                        sb.append(m.start()).append('-').append(m.end()).append(',');
                    }
                }
            }
            System.out.println(sb);
        }
    }

    /** Fewer code points behind than the body's minimum: the lowest start is still tried. */
    static void shortBehind() {
        String[] ps = {"(?<!(?=(.))\\w\\w\\w)x", "(?<!(?>(\\w)))x", "(?<=(?=(.))\\w\\w\\w)x", "(?<!(\\w)\\w)x", "(?<!(?=(.))\\w\\w\\w)" + E, "(?<!(?=(.))\\w\\w\\w)x"};
        String[] ts = {"x", "-x", "--x", "---x", E + "x", "-" + E + "x"};
        for (String raw : new String[] {"", "|" + E}) {
            String[] with = new String[ps.length];
            for (int k = 0; k < ps.length; k++) {
                with[k] = ps[k] + raw;
            }
            rows(with, ts);
        }
    }

    /** A lookbehind with transparent bounds leaves the region start where it was. */
    static void regionRestored() {
        String[] ps = {"(?<=" + E + ")?^b", "(?<=" + E + ")?\\Gb", "(?<=a)?^b", "(?<=a)?\\Ab", "(?<=" + E + ")?b$"};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (int tb = 0; tb < 2; tb++) {
                for (int ab = 0; ab < 2; ab++) {
                    Matcher m = Pattern.compile(p).matcher("xxbb");
                    m.region(2, 4).useTransparentBounds(tb == 1).useAnchoringBounds(ab == 1);
                    sb.append(" t").append(tb).append('a').append(ab).append(':');
                    while (m.find()) {
                        sb.append(m.start()).append('-').append(m.end()).append(',');
                    }
                }
            }
            System.out.println(sb);
        }
    }

    /** A literal run that ends with a high surrogate, repeated: it must not split a pair. */
    static void loops() {
        String[] ps = {"(?:A\\ud83d)+", "(?:A\\ud83d)?", "(?:A\\ud83d)*", "(?:A\\ud83d){1}", "(?:A\\ud83d){1,2}", "(?:A\\ud83d)+?", "(?:A\\ud83d)++", "(?:A\\ud83d|B)+", "(?:AB\\ud83d)+",
            "(?:A\\ud83d)+x", "(?:A\\ud83d)+\\ude00"};
        String[] ts = {"xA" + E, "xA\ud83d", "xA\ud83dx", "xA\ud83dA\ud83dy", "xAB" + E, "A\ud83d" + E + "A\ud83d"};
        rows(ps, ts);
    }

    /** '?' on an atomic group with an unbounded body is checked for overflow. */
    static void atomicQuestion() {
        String[] bodies = {"a(?>b+)?", "a(?>b+)?c", "(?>b+)?", "a(?>b+)", "a(?>b+)??", "a(?>b+)?+", "a(?:b+)?", "a(?>b*)?", "a(?>b{2,})?", "a(?>b+)?(?>c+)?", "a(?>b|c)?",
            "a(?>(b+))?", "a(?>bc)?", "x(?>y+)?z+", "(?>b+)?a", "a(?>b++)?"};
        for (String b : bodies) {
            StringBuilder sb = new StringBuilder(esc(b) + ":");
            for (String pre : new String[] {"(?<=", "(?<!"}) {
                sb.append(" [").append(finds(pre + b + ")", "abbc")).append(']');
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) {
        wrapped();
        wrappedRegions();
        shortBehind();
        regionRestored();
        loops();
        atomicQuestion();
        System.out.println("done");
    }
}
