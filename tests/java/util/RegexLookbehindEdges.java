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

    /** A wrapped reach is bounded by the chars after the position up to the end of the text. */
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

    /**
     * A reach that wrapped to -k is a bound in chars: as many before the position as k code points
     * take after it, up to the end of the text, so a pair after the position lets the search go
     * one char further back.
     */
    static void wrappedFloor() {
        String[] ps = {"(?<=a*b?" + E + ")x", "(?<=c(y)?[b" + E + "]*)x|" + E, "(?<!_*\\S.+)|" + E,
            "(?<=x*\\S.{0,2147483647})\\w|" + E, "(?<=x*\\S.{0,2147483646})\\w|" + E,
            "(?<!x*\\S.{0,2147483645})|" + E, "(?<=x*\\S\\S.{0,2147483644})\\w|" + E, "(?<=x*\\S?.{0,2147483645})\\w|" + E};
        String[] ts = {"ab" + E + "x", "c" + E + "xz", "aa" + E, "x" + E + "x" + E + "x", E + E + "xx", "bb" + E + "b" + E + "x", "abcdef"};
        rows(ps, ts);
        String t = "ab" + E + "c" + E + E + "de";
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (int[] r : new int[][] {{1, 6}, {2, 5}, {0, 4}, {3, 9}}) {
                for (int tb = 0; tb < 2; tb++) {
                    Matcher m = Pattern.compile(p).matcher(t);
                    m.region(r[0], r[1]).useTransparentBounds(tb == 1);
                    sb.append(' ');
                    while (m.find()) {
                        sb.append(m.start()).append('-').append(m.end()).append(',');
                    }
                }
            }
            System.out.println(sb);
        }
    }

    /**
     * Fewer code points behind than the body's minimum, counted over the text: the lowest start is
     * tried only when it lies in the region (or the bounds are transparent), so a start outside an
     * opaque region sets neither a group nor hitEnd.
     */
    static void lowestStart() {
        String[] ps = {"(?<!(?=(.))[\\w" + E + "]\\w)", "(?<=(?=(.))[\\w" + E + "]\\w\\w)", "(?<![\\w" + E + "](\\w))"};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(esc(p) + ":");
            for (int[] r : new int[][] {{0, 0}, {1, 1}, {1, 2}, {2, 3}, {0, 1}, {3, 3}}) {
                for (int tb = 0; tb < 2; tb++) {
                    Matcher m = Pattern.compile(p).matcher("abc");
                    m.region(r[0], r[1]).useTransparentBounds(tb == 1);
                    sb.append(' ');
                    while (m.find()) {
                        sb.append(m.start()).append('g').append(m.start(1)).append(',');
                    }
                    sb.append(m.hitEnd() ? 'h' : '-');
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

    /** The repeats inside '?' on an atomic group are checked for overflow against the total before it. */
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

    /**
     * '?', '??' and '?+' on an atom that is not a (..) or (?:..) group, such as an atomic group or
     * \R, check the repeats inside it against the running total; an atomic group ends a restart
     * made inside it, and one made before it carries on into it.
     */
    static void atomicStretch() {
        String[] bodies = {"(?>b+c)?", "(?>\\s*x)?", "a(?>b+|c)?", "x*(?>b+|c)?", "a+\\R?", ".*\\R??\\d", "a+\\R?+b",
            "x*\\S(?>b+)?", "a+(?>x\\s*)?", "a+(?>\\s*x)?", "(?>b+|c)?(?>\\s*x)?", "(?>b+|c)?(?>c(?>b+)?)?",
            "(?>(?:b|c)d+)b+", "(?:(?:b|c)d+)(?>b+)?", "(?:b|c)d+(?>b+)?", ".{0,2147483000}(?:a|b)(?>c{1000})?",
            ".{0,2147483000}(?:a|b).{0,2147483000}(?>c{1000})?", "(?>\\s*x)?+", "a+(?>b{2147483640})?+",
            "a*(?>b{3}c{2})?+", "x*\\S(?>x\\s*)?+", ".{1,2147483640}(?>(?:b|c)d+)?d+", "a+(?>(?>b+)?)?"};
        String[] ts = {"abbc", "a\r\nb", "xbbc  xc", "a1b"};
        for (String b : bodies) {
            StringBuilder sb = new StringBuilder(esc(b) + ":");
            for (String pre : new String[] {"(?<=", "(?<!"}) {
                for (String t : ts) {
                    sb.append(" [").append(finds(pre + b + ")[bc]", t)).append(']');
                }
            }
            System.out.println(sb);
        }
    }

    /** Forty nested '?' on atomic groups compile at once: each level is studied once. */
    static void nestedQuestions() {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int k = 0; k < 40; k++) {
            open.append("(?>");
            close.append(")?");
        }
        System.out.println("nested ?: [" + finds("(?<=" + open + "a" + close + ")b", "aab") + "]");
    }

    public static void main(String[] args) {
        wrapped();
        wrappedRegions();
        wrappedFloor();
        lowestStart();
        shortBehind();
        regionRestored();
        loops();
        atomicQuestion();
        atomicStretch();
        nestedQuestions();
        System.out.println("done");
    }
}
