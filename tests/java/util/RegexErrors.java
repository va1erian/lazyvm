package util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** PatternSyntaxException: description, index, pattern and the exact multi-line message. */
public class RegexErrors {
    static void bad(String re) {
        bad(re, 0);
    }

    static void bad(String re, int flags) {
        try {
            Pattern.compile(re, flags);
            System.out.println(re + " : ok");
        } catch (PatternSyntaxException e) {
            String msg = e.getMessage().replace("\r", "").replace("\n", "|");
            System.out.println(re + " : desc=" + e.getDescription() + " index=" + e.getIndex()
                + " pattern=" + e.getPattern().equals(re) + "\n    " + msg);
        }
    }

    public static void main(String[] args) {
        String[] unbalanced = {"(", ")", "a)", "(a", "((a)", "(a))", "[", "[a", "[]", "[^", "[^]", "[a-", "[a-z&&[", "[[]", "[a[]", "[\\", "(?<name>", "(?:", "(?=a", "(?>a", "(?i:a"};
        for (String re : unbalanced) {
            bad(re);
        }
        String[] dangling = {"*", "+a", "?", "a**", "a+*", "a?*", "a*?+", "a*??", "a{1}*", "(*)", "a|*", "^*+?", "x{2}?+*"};
        for (String re : dangling) {
            bad(re);
        }
        String[] repetition = {"a{", "a{1", "a{1,", "a{1,2", "a{x}", "a{,3}", "a{}", "a{-1}", "a{1x}", "a{1,x}", "a{1,2,3}", "a{2,1}",
            "a{2147483648}", "a{1,2147483648}", "ab{", "\\b{", "a{ 1}", "a{1 }", "{1}", "{x}", "{", "a|{1}"};
        for (String re : repetition) {
            bad(re);
        }
        String[] ranges = {"[z-a]", "[a-\\d]", "[\\d-z]", "[a-\\p{L}]", "[a--]", "[--a]", "[a-]", "[-a]", "[b-a]", "[\\w-\\d]", "[a-\\"};
        for (String re : ranges) {
            bad(re);
        }
        String[] escapes = {"\\", "\\x", "\\xg", "\\x{", "\\x{}", "\\x{zz}", "\\x{110000}", "\\x{10FFFF}", "\\x{41", "\\u12", "\\u123g", "\\c", "\\0", "\\09",
            "\\08", "\\0377", "\\0400", "\\N", "\\X", "\\C", "\\y", "\\E", "a\\E", "\\Qabc", "\\Qa\\E\\E", "[\\R]", "[\\b]", "[\\B]", "[\\A]", "[\\z]", "[\\1]", "[\\E]",
            "\\i", "\\_", "\\-", "\\e", "\\a", "\\cA"};
        for (String re : escapes) {
            bad(re);
        }
        String[] properties = {"\\p", "\\P", "\\p{", "\\p{}", "\\p{ }", "\\p{=}", "\\p{L", "\\p{Foo}", "\\p{IsFoo}", "\\p{InFoo}", "\\p{Is}", "\\p{In}", "\\p{gc=}",
            "\\p{gc=Foo}", "\\p{sc=Foo}", "\\p{blk=Foo}", "\\p{foo=bar}", "\\p{javaFoo}", "\\p{alpha}", "\\p{Greek}", "\\p{isAlpha}", "\\p{L}", "\\p{IsL}", "\\pL", "\\p{Lu}",
            "\\p{IsAlphabetic}", "\\p{InGreek}", "\\p{IsLatin}", "\\p{javaLowerCase}", "\\p{Alpha}", "\\p{general_category=Lu}", "\\p{Upp=er}", "\\p{sc=latin}"};
        for (String re : properties) {
            bad(re);
        }
        String[] groups = {"(?", "(?z)", "(?a)", "(?i", "(?-", "(?i:", "(?i-", "(?)", "(?-)", "(?-:a)", "(?^i)", "(?#x", "(?$a)", "(?@a)", "(?<", "(?<>x)", "(?<1>x)", "(?<1a>x)",
            "(?<_a>x)", "(?<a_b>x)", "(?<a-b>x)", "(?<a", "(?<ab", "(?<a>", "(?<a>x", "(?<a>x)(?<a>y)", "(?<a>x)(?<b>y)", "(?<A>x)(?<a>y)", "(?<a1>x)", "\\k", "\\k<", "\\k<>",
            "\\k<x>", "\\k<a>(?<a>x)", "(?<a>x)\\k<a>", "(?<a>x)\\k<b>", "(?<a>x)\\k<a", "(?<=a*)", "(?<=a+)b", "(?<=ab*)", "(?<=a.*b)", "(?<=.*)", "(?<=(a|bc)*)", "(?<!a|bc)",
            "(?<=\\1)", "(?<*=a)", "(?<.!c)", "(?<=a)?", "(a)\\2", "\\1", "(a)\\10", "\\8"};
        for (String re : groups) {
            bad(re);
        }
        bad("(?x)a b # c");
        bad("(?x)a )", 0);
        bad("a )", Pattern.COMMENTS);
        bad("a * *", Pattern.COMMENTS);
        bad("a{1", Pattern.LITERAL);
        bad("(", Pattern.LITERAL);
        bad("[a", Pattern.CASE_INSENSITIVE);
        bad("a ) b", Pattern.COMMENTS);
        bad("a  # comment ) ", Pattern.COMMENTS);
        bad("(?x) ? a", 0);
        PatternSyntaxException e = new PatternSyntaxException("desc", "regex", 2);
        System.out.println(e.getMessage().replace("\r", "").replace("\n", "|") + " " + e.getIndex() + " " + e.getDescription() + " " + e.getPattern());
        e = new PatternSyntaxException("desc", "re", -1);
        System.out.println(e.getMessage().replace("\r", "").replace("\n", "|"));
        e = new PatternSyntaxException("desc", "re", 5);
        System.out.println(e.getMessage().replace("\r", "").replace("\n", "|"));
        System.out.println(e instanceof IllegalArgumentException);
        System.out.println("done");
    }
}
