package util;

import static util.RegexLimits.count;
import static util.RegexLimits.find;
import static util.RegexLimits.messageShape;
import static util.RegexLimits.rep;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The second part of RegexLimits: realistic long inputs, long classes and alternations, nested
 * groups, a long lookbehind over repeated resets, and patterns nested too deeply to compile.
 */
public class RegexLimitsSizes {
    public static void main(String[] args) {
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
