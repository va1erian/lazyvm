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

    /** A negative lookahead entered at the end sets requireEnd, and keeps it; transparent bounds move the end. */
    static void negativeLookaheadAtTheEnd() {
        Matcher m = Pattern.compile("(?:(?!\\n)A)*\\w").matcher("");
        System.out.println("skipped search " + m.find() + " " + flags(m));
        System.out.println("matches " + m.matches() + " " + flags(m) + " " + m.lookingAt() + " " + flags(m));
        Matcher t = Pattern.compile("(?!a)").matcher("0123456789");
        t.region(7, 7).useTransparentBounds(true);
        System.out.println("transparent " + t.lookingAt() + " " + flags(t) + " " + t.matches() + " " + flags(t) + " " + t.find() + " " + flags(t));
        t.region(7, 7).useTransparentBounds(false);
        System.out.println("opaque " + t.lookingAt() + " " + flags(t) + " " + t.matches() + " " + flags(t) + " " + t.find() + " " + flags(t));
    }

    /** A region that ends between the halves of a surrogate pair still reads the whole pair. */
    static void pairAtTheRegionEnd() {
        String pair = "\ud83d\ude00";
        String[] patterns = {".", "\\W", "[^a]", "\\S", ".+", "(.)", "\\p{So}", "..", "x*"};
        for (boolean transparent : new boolean[] {false, true}) {
            StringBuilder sb = new StringBuilder("pair transparent=" + transparent + ":");
            for (String re : patterns) {
                Matcher m = Pattern.compile(re).matcher(pair + "\u00c9");
                m.region(0, 1).useTransparentBounds(transparent);
                sb.append(' ').append(m.lookingAt() ? m.end() : -1).append(m.hitEnd() ? "h" : "");
                m.region(0, 1);
                sb.append(m.matches() ? "M" : "-");
            }
            System.out.println(sb);
        }
    }

    /** \G after attempts that fail: it follows the end of the last repeat iteration the attempt matched. */
    static void previousMatchEnd() {
        String[] patterns = {"\\G+", "\\G", "\\Ga", "a*\\G", "\\G+a", "(?:\\Ga)+"};
        for (String re : patterns) {
            Matcher m = Pattern.compile(re).matcher("aaxaa");
            m.region(2, 5);
            StringBuilder sb = new StringBuilder(re + " region(2,5):");
            for (String op : new String[] {"matches", "lookingAt", "find", "matches", "lookingAt"}) {
                boolean ok = op.equals("matches") ? m.matches() : op.equals("lookingAt") ? m.lookingAt() : m.find();
                sb.append(' ').append(op).append('=').append(ok ? m.start() + "-" + m.end() : "no");
            }
            System.out.println(sb);
        }
        Matcher m = Pattern.compile("a*\\G").matcher("aaxaa");
        System.out.println("a*\\G " + m.lookingAt() + " " + m.matches() + " " + m.find() + " " + m.start() + "-" + m.end());
    }

    /** Only ^ or \A written directly at the start lets a failed search skip the other positions. */
    static void anchoredStart() {
        String[] patterns = {"^a", "\\Aa", "(?i)^a", "(?:^a)", "(?:\\A)a", "(?x:\\A)b", "(^a)", "^*a", "^(?:a)", "^a|b", "(?>^)a", "(?=^)a"};
        for (String re : patterns) {
            Matcher m = Pattern.compile(re).matcher("bcd");
            boolean found = m.find();
            System.out.println("anchored " + re + " " + found + " " + flags(m));
        }
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
        ends("(a)\\1", "a");
        ends("(a)\\1", "aa");
        ends(".*", "abc");
        ends(".*c", "abc");
        ends("\\d{2,3}", "12");
        ends("\\d{2,3}", "123");
        System.out.println(Pattern.compile("a").matcher("ba"));
        negativeLookaheadAtTheEnd();
        pairAtTheRegionEnd();
        previousMatchEnd();
        anchoredStart();
        System.out.println("done");
    }
}
