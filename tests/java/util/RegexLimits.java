package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Deep loops, loops the memo cuts short, and long runs: sizes both engines handle.
 * RegexLimitsSizes has long inputs, long classes and alternations, and nested groups; the two are
 * split so each stays well under the CI time limit.
 */
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
        System.out.println("done");
    }
}
