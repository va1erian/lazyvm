package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regions, anchoring and transparent bounds, \G, reset, usePattern, hitEnd and requireEnd. */
public class RegexRegions {
    static String spans(Matcher m) {
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append('[').append(m.start()).append(',').append(m.end()).append(')');
        }
        return sb.toString();
    }

    static void check(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static String flags(Matcher m) {
        return "hit=" + m.hitEnd() + " req=" + m.requireEnd();
    }

    static void ends(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        boolean full = m.matches();
        String a = flags(m);
        m.reset();
        boolean look = m.lookingAt();
        String b = flags(m);
        m.reset();
        boolean found = m.find();
        System.out.println(re + " | " + in.replace('\r', 'R').replace('\n', 'N') + " : matches=" + full + " " + a + " ; lookingAt=" + look + " " + b + " ; find=" + found + " " + flags(m));
    }

    public static void main(String[] args) {
        Matcher m = Pattern.compile("^b|c$|\\bd").matcher("abcd bcd");
        System.out.println(spans(m));
        m.region(1, 3);
        System.out.println(m.regionStart() + " " + m.regionEnd() + " " + spans(m) + " " + m.hasAnchoringBounds() + " " + m.hasTransparentBounds());
        m.useAnchoringBounds(false).region(1, 3);
        System.out.println(spans(m) + " " + m.hasAnchoringBounds());
        m.useAnchoringBounds(true).useTransparentBounds(true).region(1, 3);
        System.out.println(spans(m) + " " + m.hasTransparentBounds());

        Matcher w = Pattern.compile("\\bb").matcher("ab b");
        w.region(1, 4);
        System.out.println(spans(w));
        w.useTransparentBounds(true).region(1, 4);
        System.out.println(spans(w));
        Matcher la = Pattern.compile("a(?=b)").matcher("ab");
        System.out.println(la.region(0, 1).find() + " " + la.useTransparentBounds(true).region(0, 1).find());
        Matcher lb = Pattern.compile("(?<=a)b").matcher("ab");
        System.out.println(lb.region(1, 2).find() + " " + lb.useTransparentBounds(true).region(1, 2).find());
        Matcher dollar = Pattern.compile("a$").matcher("ab");
        System.out.println(dollar.region(0, 1).find() + " " + dollar.useAnchoringBounds(false).region(0, 1).find());
        Matcher caret = Pattern.compile("^b").matcher("ab");
        System.out.println(caret.region(1, 2).find() + " " + caret.useAnchoringBounds(false).region(1, 2).find());
        Matcher whole = Pattern.compile("b+").matcher("abbbc");
        System.out.println(whole.region(1, 4).matches() + " " + whole.region(1, 3).matches() + " " + whole.region(0, 4).lookingAt() + " " + whole.region(2, 5).lookingAt());
        System.out.println(whole.regionStart() + " " + whole.regionEnd() + " " + whole.reset().regionEnd());
        check(() -> whole.region(-1, 2));
        check(() -> whole.region(0, 9));
        check(() -> whole.region(3, 2));
        check(() -> whole.region(6, 7));

        Matcher g = Pattern.compile("\\Ga").matcher("aab a");
        System.out.println(spans(g) + " " + g.reset().find(1) + " " + g.start() + " " + g.find() + " " + g.find());
        Matcher g2 = Pattern.compile("\\G\\d").matcher("12x3");
        System.out.println(spans(g2));

        Matcher r = Pattern.compile("a").matcher("aaa");
        r.find();
        r.find();
        System.out.println(r.start() + " " + r.reset().find() + " " + r.start() + " " + r.reset("xa").find() + " " + r.start());
        System.out.println(r.find(0) + " " + r.start() + " " + r.find() + " " + r.hitEnd());
        r.reset("aaa").region(1, 2);
        r.reset();
        System.out.println(r.regionStart() + " " + r.regionEnd());

        Matcher u = Pattern.compile("(a)(b)?").matcher("ab ab");
        u.find();
        Matcher same = u.usePattern(Pattern.compile("(b)"));
        System.out.println((same == u) + " " + u.groupCount() + " " + u.find() + " " + u.start() + " " + u.group(1) + " " + u.pattern());
        u.usePattern(Pattern.compile("(a)(b)"));
        System.out.println(u.find() + " " + u.reset().find() + " " + u.start() + " " + u.group(2));
        check(() -> u.usePattern(null));
        Matcher ua = Pattern.compile("a").matcher("aXa");
        ua.find();
        StringBuffer sb = new StringBuffer();
        ua.appendReplacement(sb, "1");
        ua.usePattern(Pattern.compile("X"));
        ua.find();
        ua.appendReplacement(sb, "2");
        ua.appendTail(sb);
        System.out.println(sb);

        ends("a", "a");
        ends("a", "");
        ends("a", "ab");
        ends("a+", "aa");
        ends("a+", "aab");
        ends("ab", "a");
        ends("ab", "ba");
        ends("a|ab", "a");
        ends("a|ab", "ab");
        ends("a*", "");
        ends("a?", "a");
        ends("a$", "a");
        ends("a$", "ab");
        ends("a\\z", "a");
        ends("a\\Z", "a\n");
        ends("a\\b", "a");
        ends("a\\b", "ab");
        ends("\\b", "");
        ends("(?m)a$", "a\na");
        ends("(?m)^", "");
        ends("(?m)^a", "b\na");
        ends("(?!a)", "");
        ends("(?!a)", "b");
        ends("a(?!b)", "a");
        ends("a(?=b)", "a");
        ends("(?<=a)", "a");
        ends("\\R", "\r");
        ends("\\R", "\r\n");
        ends("x\\R", "x");
        ends("(a)\\1", "a");
        ends("(a)\\1", "aa");
        ends(".*", "abc");
        ends(".*?", "abc");
        ends(".*c", "abc");
        ends("[ab]+c", "abab");
        ends("\\d{2,3}", "12");
        ends("\\d{2,3}", "123");
        ends("\\d{2,3}", "1234");
        System.out.println(Pattern.compile("a").matcher("ba"));
        System.out.println("done");
    }
}
