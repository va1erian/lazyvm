package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which lookbehind bodies compile, and how far back they look. The accepted set follows the oracle
 * (docs/regex.md, "Lookbehind reach"), including bodies it accepts although they are unbounded.
 */
public class RegexLookbehind {
    private static final String[] TEXTS = {"aaaaaaab_a", "a_aab_aaa_b_a", "xaaabbbaabbb_ab", " KΑabc Β_ def", "bbcxbcc_x"};

    static int count(String re, String text) {
        Matcher m = Pattern.compile(re).matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static void body(String b) {
        StringBuilder sb = new StringBuilder(b + " :");
        try {
            Pattern.compile("(?<=" + b + ")");
        } catch (PatternSyntaxException e) {
            System.out.println(sb + " rejected @" + e.getIndex());
            return;
        }
        sb.append(" ok");
        for (String t : TEXTS) {
            sb.append(' ').append(count("(?<=" + b + ")", t)).append('/').append(count("(?<!" + b + ")", t));
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        String[] bounded = {"a", "ab", "a|bc", "[ab]", "\\w", ".", "a{2,5}", "(?:ab){2,3}", "x(?:a|b)", "(a)", "(a+)", "a\\R", "\\b", "^a", "a$", "(?=a)b", "a|b+"};
        String[] unbounded = {"a+", "a*", ".*", ".+", "\\w+", "a{2,}", "a+?", "a*?", "(a)+", "(?:a)+", "(?>a|b)+", "(?>a)+", "[ab]++", "a++", "\\w+x", "a+b", "a*a", "a+a", "a+aa", "a{3,}b", "(a+)_", "(a)+_"};
        String[] afterUnbounded = {"a+_?", "a*_", "a+_??", "a+_?_?", "a+__", "a+(?=b)_", "[ab]+_?+", "a+(?:ab)?", "a+(?:a|b)?", "a+(?:a|b)", "a+(?:_)", "a+b|c+", "x|a+", "a{2,}_??", "a+.", ".+a"};
        String[] rejected = {"_a+", "_?a+", "ab*", "a.*b", "x\\w+", "a+b+", "a+b*", "b*a+", "a?b+", "a?b*", "a?+b*", "aa*", "a_*", "a+_*", "a+_{0,1}", "a+_{1}", "..+", ".a+", "\\R+", "a{2,}b{2}", "a{1,2}b+"};
        String[] groupLoops = {"(?:a|b)+", "(?:a|b)*", "(?:a|b){2}", "(?:a|b){0,3}", "(a|bc){2}", "(?: |x){2,}", "(?:ab)+", "(?:ab)*", "(?:ab)*a", "(?:a|bc)+", "(?:a?b)+", "(?:a{2})+", "(?:a+)+", "(?:a+_?)+", "(?:a)+_"};
        String[] restarts = {"(?:a|b)a+", "(?:a|bc)a+", "(?:ab|cd)a+", "(a|b)a+", "(?:a|b)a+b", "(?:a|b)_?a+", "x(?:a|b)a+", "a(?:a|b)?a+", "(?:ab)?a+", "(?:ab)??a+", "(?:ab)?+a+", "(?:ab)?a+b+", "(?:ab)?_a+", "_(?:ab)?a+",
            "(?:ab)?(?:cd)?a+", "(?:ab)?(?:cd)a+", "(?:(?:ab)?a)a+", "(?:a(?:ab)?)a+", "(?:a(?:ab)?)_a+", "(?:ab)?(?:a|b)a+", "(?:ab)?a+(?:ab)?a+", "(a)?a+", "(?:a)?a+", "a?a+", "(?>ab)?a+", "ab?a+", "(?:ab)?+_", ".+(?:ab)?+a{2}"};
        String[] wraps = {"(?:ab)+", "(?:abc)+", "(?:abcd)+", "(?:abcde)+", "(?:abc)*", "(?:ab)*", "(?:abc){2,}", "(?:abc){3,}", "(?:ab){2,}", "(?:a){2,}", "(?:aa)+", "(?:aaa)+", "(?:\\w\\w)+", "(?:\\w\\w\\w)+"};
        String[] looks = {"(?:(?=b)b){2}", "(b(?=c)){1,2}", "(?:a(?!b)){2}", "(?:(?<=a)a)+", "(?:(?=b)b)+", "(?:b(?=c)|c)+", "(?:(?=b)bb)*", "(?:(?=b)b)?"};
        String[] alternations = {"a*_?|c*_?", "a+_?|c+_?", "a*|c*", "a+|c+", "b*_?|c", "a*_?|c*_?|_", "_?a*|c", "(?:a*_?|c*_?)", "a*_?|c*_?x"};
        String[][] groups = {bounded, unbounded, afterUnbounded, rejected, groupLoops, restarts, wraps, looks, alternations};
        String[] names = {"bounded", "unbounded", "after an unbounded repeat", "rejected", "loops over groups", "restart after an alternation or optional group",
            "group repeats: an odd body length wraps to a positive reach, an even one to a negative reach", "a lookaround inside a repeated group has no width",
            "an alternation counts its longest branch from -1"};
        for (int g = 0; g < groups.length; g++) {
            System.out.println("== " + names[g]);
            for (String b : groups[g]) {
                body(b);
            }
        }
        System.out.println("== a reach that wraps around");
        String text = " KΑabc Β_ def";
        System.out.println(count("(?<!_*\\p{InBasicLatin}.+)", text) + " " + count("(?<=_*\\p{InBasicLatin}.+)", text) + " " + count("(?<!_*\\p{InBasicLatin})", text));
        System.out.println(count("(?<=a+b|c+d)", "aab ccd") + " " + count("(?<!a+b|c+d)", "aab ccd") + " " + count("(?<=a*a)", "baab") + " " + count("(?<=a*aa)", "baab"));
        System.out.println("done");
    }
}
