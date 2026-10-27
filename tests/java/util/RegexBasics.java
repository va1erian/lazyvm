package util;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The core Pattern/Matcher surface: matches, lookingAt, find, groups, positions. */
public class RegexBasics {
    static String show(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c < 32 || c > 126) {
                sb.append("\\u").append(Integer.toHexString(c | 0x10000).substring(1));
            } else {
                sb.append(c);
            }
        }
        return sb.append("'").toString();
    }

    static String groups(Matcher m) {
        StringBuilder sb = new StringBuilder("[" + m.start() + "," + m.end() + ")");
        for (int g = 0; g <= m.groupCount(); g++) {
            sb.append(' ').append(show(m.group(g)));
        }
        return sb.toString();
    }

    static void all(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        StringBuilder sb = new StringBuilder(show(re) + " in " + show(in) + ":");
        while (m.find()) {
            sb.append(' ').append(groups(m));
        }
        System.out.println(sb);
    }

    static void whole(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        System.out.println(show(re) + " vs " + show(in) + ": matches=" + m.matches() + " hitEnd=" + m.hitEnd()
            + " requireEnd=" + m.requireEnd() + " lookingAt=" + m.reset().lookingAt());
    }

    static void check(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        all("a", "banana");
        all("an", "banana");
        all("(a)(n)?", "banana");
        all("[aeiou]+", "education queue");
        all("\\d+", "a1b22c333");
        all("\\w+@\\w+\\.com", "x bob@site.com, al@ex.com");
        all("(\\d{4})-(\\d{2})-(\\d{2})", "on 2024-02-29 and 1999-12-31.");
        all("^", "abc");
        all("$", "abc");
        all("", "ab");
        all("x*", "axxb");
        all("\\b", "hi you");
        all("(?<k>\\w)=(?<v>\\w)", "a=1 b=2");
        whole("a+", "aaa");
        whole("a+", "aaab");
        whole("a+", "");
        whole("abc", "ab");
        whole("a|ab", "ab");
        whole("(a|ab)(c|bcd)", "abcd");
        whole("a*", "aaa");
        whole("a$", "a");
        whole("a$", "a\n");
        whole("\\Qa.b\\E", "a.b");

        Matcher m = Pattern.compile("(?<word>[a-z]+)(\\d)?").matcher("ab1 cd");
        check(() -> m.group());
        check(() -> m.start());
        check(() -> m.end());
        check(() -> m.start(1));
        System.out.println(m.groupCount() + " " + m.pattern() + " " + m.hasTransparentBounds() + " " + m.hasAnchoringBounds());
        System.out.println(m.find() + " " + groups(m) + " word=" + m.group("word") + " " + m.start("word") + " " + m.end("word"));
        check(() -> m.group(3));
        check(() -> m.group(-1));
        check(() -> m.group("nope"));
        check(() -> m.start("nope"));
        check(() -> m.group((String) null));
        System.out.println(m.find() + " " + groups(m) + " " + m.start(2) + " " + m.end(2));
        System.out.println(m.find());
        check(() -> m.group());
        System.out.println(m.find(1) + " " + groups(m));
        check(() -> m.find(-1));
        check(() -> m.find(99));
        System.out.println(m.reset("zz99").find() + " " + groups(m));
        System.out.println(m.reset().lookingAt() + " " + m.matches());
        System.out.println(m);

        Pattern p = Pattern.compile("a(b)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        System.out.println(p + " " + p.pattern() + " " + p.flags());
        System.out.println(Pattern.matches("[a-c]+", "abcabc") + " " + Pattern.matches("[a-c]+", "abcd"));
        System.out.println(Pattern.compile("(?i)x").flags() + " " + Pattern.compile("(?:(?i)x)").flags());
        System.out.println(Pattern.compile("^b").asPredicate().test("abc") + " " + Pattern.compile("b").asPredicate().test("abc"));
        System.out.println(Pattern.quote("a.b") + " " + Pattern.quote("x\\Ey") + " " + Pattern.quote(""));
        System.out.println("a.b".matches(Pattern.quote("a.b")) + " " + "axb".matches(Pattern.quote("a.b")));

        Matcher f = Pattern.compile("(a)|(b)").matcher("ab");
        f.find();
        MatchResult r = f.toMatchResult();
        f.find();
        System.out.println(r.start() + " " + r.end() + " " + r.group() + " " + r.group(1) + " " + r.group(2) + " " + r.groupCount());
        System.out.println(f.toMatchResult().group(2) + " " + r);
        f.find();
        check(() -> f.toMatchResult().group());
        check(() -> f.toMatchResult().start());
        check(() -> Pattern.compile(null));
        check(() -> Pattern.compile("a").matcher(null));
        System.out.println("hello world".replaceAll("o", "0") + " " + "hello".replaceFirst("l", "L") + " " + "abc".matches("a.c"));
        System.out.println("done");
    }
}
