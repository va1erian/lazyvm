package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Deep loops, long classes and alternations, and nested groups: sizes both engines handle. */
public class RegexLimits {
    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    /** Whether re matches unit repeated n times; a stack overflow is reported, not thrown. */
    static void loop(String re, String unit, int n) {
        String result;
        try {
            result = String.valueOf(Pattern.compile(re).matcher(rep(unit, n)).matches());
        } catch (StackOverflowError e) {
            result = "overflow";
        }
        System.out.println(re + " x" + n + " -> " + result);
    }

    static int count(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String find(String re, String in) {
        return String.valueOf(Pattern.compile(re).matcher(in).find());
    }

    /** Whether the message is "description near index N", the pattern, and a caret under index N. */
    static String messageShape(PatternSyntaxException e, String p) {
        int at = e.getIndex();
        String nl = System.lineSeparator();
        String caret = rep(" ", Math.max(at, 0)) + "^";
        boolean whole = e.getMessage().equals(e.getDescription() + " near index " + at + nl + p + nl + caret);
        return (at > 0 && at < p.length() ? ", index inside" : ", index " + at) + (whole ? ", full message" : ", short message");
    }

    public static void main(String[] args) {
        // Loops over a single class, alone or inside one capturing group, take no stack per iteration.
        int deep = 100000;
        loop("(\\d)+", "1", 200000);
        loop("([a-z])*", "a", 200000);
        loop("(.)*", "a", 200000);
        loop("(a|b)*", "ab", 400);
        loop("(?:ab)*+", "ab", deep);
        loop("(?>a|b)*", "ab", deep);
        loop("a*", "a", deep);
        // Loops over a flat body (literals, classes, repeats of a class) keep their state in an array.
        loop("(\\w+\\s?)*", "ab ", 800);
        loop("(?:ab|c)*", "abc", 800);
        loop("(ab|c)*", "abc", 800);
        loop("(?:a|b)*?c", "a", 0);
        loop("(?:ab+;?)*", "abb", 800);
        loop("(?:(a)b)*", "ab", 500);
        loop("\\R+", "\n", 20000);
        loop("\\R+", "\r\n", 20000);
        loop("(?:\\R\\d)*", "\n1", 5000);
        // A search that would retry the same failing states exponentially is cut short by LoopMemo.
        for (String[] c : new String[][] {{"(?:x+x+)+y", "x", "22"}, {"(?:x+x+)+y", "x", "26"}, {"(.*?,){11}P", "1,", "20"},
            {"(a+)+b", "a", "26"}, {"(a|aa)+$", "a", "26"}, {"(a*)*b", "a", "26"}, {"(?:a|ab)+c", "ab", "13"},
            {"(?:(a)(a)?)+b", "a", "30"}}) {
            System.out.println(c[0] + " x" + c[2] + " " + find(c[0], rep(c[1], Integer.parseInt(c[2])) + "!"));
        }
        // A loop inside another loop does not remember failures: whether it can finish depends on the outer count.
        String block = rep("a", 300) + "d";
        System.out.println(find("(?:(?:aa)+){2}c", rep(block, 3) + rep("a", 300) + "c"));
        // Searches that try every start over a long run stay linear: the rest of a run is skipped after a failure.
        System.out.println(find("\\s+$", rep(" ", 20000) + "x") + " " + find("\\s+$", rep(" ", 20000)) + " " + find("\\s+\\z", rep(" ", 20000) + "\n"));
        System.out.println(find(".*foo", rep("x", 30000)) + " " + count(".*foo", rep("x", 30000) + "\n" + rep("y", 30000) + "foo") + " " + find("x*y", rep("x", 20000)));
        System.out.println(count("[a-z]*y", rep("x", 20000) + "y" + rep("x", 20000)) + " " + count("\\d+;", rep("1", 20000) + ";" + rep("2", 20000)));
        // A lookbehind over an unbounded body only tries the starts the body can begin at.
        System.out.println(find("(?:(?<=(a)+)a|aa)*b", rep("z", 21000) + rep("a", 12) + "x aaab") + " " + find("(?<=a+)b", rep("z", 30000) + "ab"));
        // Realistic long inputs: a log, CSV, markup with lazy dots.
        String markup = rep("<b>" + rep("x", 50) + "</b> ", 2000);
        System.out.println(count("(?s)<b>.*?</b>", markup) + " " + markup.replaceAll("<[^>]+>", "").length() + " " + count("\"(?:[^\"]|\"\")*\"|[^,]+", rep("\"a,b\",\"c\"\"d\",e,", 5000)));
        String log = rep("2024-05-01 12:00:01 INFO request served in 12 ms\n", 2000);
        System.out.println(count("(?m)^.*ERROR.*$", log) + " " + count("(?m)^\\S+ \\S+ (\\w+) ", log) + " " + count("\\b\\w+\\b", log) + " " + log.split("\\s+").length);
        Matcher m = Pattern.compile("(\\d)+").matcher(rep("12", 2000));
        System.out.println(m.matches() + " " + m.group(1) + " " + m.start(1) + " " + m.end(1));
        m = Pattern.compile("(\\w+\\s?)*").matcher(rep("ab ", 1000) + "cd");
        System.out.println(m.matches() + " [" + m.group(1) + "] " + m.start(1) + " " + m.end(1));
        m = Pattern.compile("(ab|c)*d").matcher(rep("abc", 700) + "d");
        System.out.println(m.matches() + " " + m.group(1) + " " + m.start(1));
        // A long class and a long alternation compile and match.
        StringBuilder chars = new StringBuilder();
        StringBuilder alternatives = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            char c = (char) (0x4e00 + i);
            chars.append(c);
            alternatives.append(i == 0 ? "" : "|").append(c);
        }
        String cjk = chars.toString();
        System.out.println(find("[" + cjk + "]", "x丐") + " " + find("[" + cjk + "]", "x　") + " " + find("[^" + cjk + "]", "丐") + " "
            + find("(?i)[" + cjk + "]", "丐") + " " + find("[a-c" + cjk + "&&[^丑]]+", "丑"));
        System.out.println(find(alternatives.toString(), "x丐") + " " + find("(" + alternatives + ")", "渏") + " " + find("(" + alternatives + ")", "渐")
            + " " + find("(?:" + alternatives + ")+x", "丐丑x"));
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            words.append(i == 0 ? "" : "|").append('w').append(i);
        }
        System.out.println(find("(?:" + words + ")x", "zw2999x") + " " + find(words.toString(), "w3000"));
        // Nested groups are parsed without recursion.
        for (int depth : new int[] {10, 300}) {
            System.out.println("nesting " + depth + " " + find(rep("(?:", depth) + "a" + rep(")", depth), "a") + " " + find(rep("(", depth) + "a" + rep(")", depth), "a")
                + " " + find(rep("(?=", depth) + "a" + rep(")", depth), "a") + " " + find(rep("(?>", depth) + "a" + rep(")", depth), "a")
                + " " + find(rep("(?i:", depth) + "a" + rep(")", depth), "A"));
        }
        m = Pattern.compile(rep("(", 200) + "a" + rep(")", 200)).matcher("a");
        System.out.println(m.matches() + " " + m.groupCount() + " " + m.group(1) + " " + m.group(200) + " " + m.start(100));
        // find(int), region and reset over one String keep what a long lookbehind learned about
        // the text (an index of the starts it can use), so this finishes in a moment; with the
        // index rebuilt every time it takes minutes.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60000; i++) {
            text.append(i % 100 == 99 ? 'Z' : 'x');
        }
        String longText = text.toString();
        Matcher look = Pattern.compile("Z(?<=x[^q]{0,3000})").matcher(longText);
        long start = System.nanoTime();
        int found = 0;
        for (int pos = 2500; pos < 52500; pos += 500) {
            if (look.find(pos)) {
                found++;
            }
            if (look.region(pos, 60000).find()) {
                found++;
            }
            look.reset();
            if (look.find()) {
                found++;
            }
        }
        long millis = (System.nanoTime() - start) / 1000000;
        System.out.println("repeated resets over a String " + found + (millis < 30000 ? " fast" : " slow"));
        // A pattern nested too deeply to compile is reported as a syntax error. The depth at which
        // that happens differs between engines, so only a depth that both reject is tried. The
        // index depends on the stack as well, so only the shape of the message is printed.
        for (String open : new String[] {"(", "(?=", "(?<="}) {
            String p = rep(open, 20000) + "a" + rep(")", 20000);
            try {
                Pattern.compile(p);
                System.out.println("nesting 20000 " + open + " compiled");
            } catch (PatternSyntaxException e) {
                System.out.println("nesting 20000 " + open + " " + e.getDescription() + messageShape(e, p));
            } catch (StackOverflowError e) {
                System.out.println("nesting 20000 " + open + " StackOverflowError");
            }
        }
        System.out.println("done");
    }
}
