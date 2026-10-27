package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** replaceAll, replaceFirst, appendReplacement/appendTail and quoteReplacement. */
public class RegexReplace {
    static void all(String re, String in, String rep) {
        try {
            System.out.println(re + " | " + in.replace("\n", "\\n") + " | " + rep + " => '"
                + Pattern.compile(re).matcher(in).replaceAll(rep).replace("\n", "\\n") + "'");
        } catch (RuntimeException e) {
            System.out.println(re + " | " + in + " | " + rep + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static void first(String re, String in, String rep) {
        try {
            System.out.println(re + " | " + in + " | " + rep + " => '" + Pattern.compile(re).matcher(in).replaceFirst(rep) + "'");
        } catch (RuntimeException e) {
            System.out.println(re + " | " + in + " | " + rep + " => " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        all("a", "banana", "o");
        all("a", "banana", "");
        all("an", "banana", "[$0]");
        all("(a)(n)", "banana", "$2$1");
        all("(a)(n)", "banana", "<$1><$2><$3>");
        all("(a)(n)", "banana", "$10");
        all("(a)(n)", "banana", "$01");
        all("(a)(n)", "banana", "$12");
        all("(?<first>a)(?<second>n)", "banana", "${second}${first}");
        all("(?<first>a)(n)", "banana", "${first}-$2");
        all("a", "banana", "\\$");
        all("a", "banana", "\\\\");
        all("a", "banana", "$$");
        all("(a)?b", "ab b", "[$1]");
        all("x*", "abc", "-");
        all("", "abc", "-");
        all("^", "a\nb", ">");
        all("(?m)^", "a\nb", ">");
        all("$", "a\nb", "<");
        all("(?m)$", "a\nb", "<");
        all("\\b", "ab cd", "|");
        all("\\s+", "  a   b  ", " ");
        all("(\\w+) (\\w+)", "hello world foo bar", "$2 $1");
        all("(.)\\1", "aabbcd", "<$1>");
        all("\\d+", "a1b22", "{$0}");
        all("(?i)A", "aAa", "x");
        all("b+", "abbbc", "$0$0");
        all("é", "café", "e");
        all(".", "a😀b", "x");
        all("a", "a", "é😀");
        all("a", "a", "$");
        all("a", "a", "$x");
        all("a", "a", "$ 1");
        all("a", "a", "$9");
        all("a", "a", "$1");
        all("(a)", "a", "${");
        all("(a)", "a", "${}");
        all("(a)", "a", "${x");
        all("(a)", "a", "${x}");
        all("(a)", "a", "${1}");
        all("(a)", "a", "\\");
        all("(a)", "a", "x\\");
        all("b", "a", "$");
        all("b", "a", "\\");
        first("a", "banana", "o");
        first("a", "banana", "$0$0");
        first("(a)(n)", "banana", "$2$1");
        first("z", "banana", "o");
        first("z", "banana", "$");
        first("a", "banana", "$");
        first("^", "abc", ">");
        first("", "abc", "-");
        first("(?<n>a)", "banana", "${n}!");
        // appendReplacement / appendTail
        Matcher m = Pattern.compile("(\\d+)").matcher("a1b22c333d");
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "<" + (Integer.parseInt(m.group(1)) * 2) + ">");
        }
        System.out.println(m.appendTail(sb));
        sb.setLength(0);
        m.reset();
        m.find();
        m.appendReplacement(sb, "X");
        System.out.println(sb);
        try {
            m.appendReplacement(sb, "Y");
        } catch (IndexOutOfBoundsException e) {
            System.out.println("IOOBE " + e.getMessage() + " sb=" + sb);
        }
        m.find();
        m.appendReplacement(sb, "$1$1");
        m.appendTail(sb);
        System.out.println(sb);
        m.appendTail(sb);
        System.out.println(sb);
        m.reset();
        sb.setLength(0);
        m.find();
        m.find();
        m.appendReplacement(sb, "Z");
        System.out.println(sb);
        try {
            m.reset();
            m.appendReplacement(sb, "x");
        } catch (IllegalStateException e) {
            System.out.println("ISE " + e.getMessage());
        }
        try {
            m.reset();
            m.find();
            sb.setLength(0);
            m.appendReplacement(sb, "$5");
        } catch (IndexOutOfBoundsException e) {
            System.out.println("IOOBE " + e.getMessage() + " sb=" + sb);
        }
        try {
            m.appendReplacement(sb, null);
        } catch (NullPointerException e) {
            System.out.println("NPE");
        }
        try {
            Pattern.compile("a").matcher("a").replaceAll(null);
        } catch (NullPointerException e) {
            System.out.println("NPE replaceAll");
        }
        try {
            Pattern.compile("a").matcher("a").replaceFirst(null);
        } catch (NullPointerException e) {
            System.out.println("NPE replaceFirst " + e.getMessage());
        }
        System.out.println(Pattern.compile("a").matcher("b").replaceAll((String) "$"));
        // quoteReplacement
        String tricky = "a$1\\b$";
        System.out.println(Matcher.quoteReplacement(tricky) + " " + Matcher.quoteReplacement("plain") + " " + Matcher.quoteReplacement(""));
        System.out.println("x".replaceAll("x", Matcher.quoteReplacement(tricky)));
        try {
            Matcher.quoteReplacement(null);
        } catch (NullPointerException e) {
            System.out.println("NPE quote");
        }
        // String methods
        System.out.println("a.b.c".replace(".", "-") + " " + "a.b.c".replaceAll(".", "-") + " " + "a.b.c".replace('.', '/'));
        System.out.println("hello".replaceAll("l+", "L") + " " + "hello".replaceFirst("l+", "L") + " " + "hello".replaceAll("^h|o$", "_"));
        System.out.println("a b".replaceAll("(\\w) (\\w)", "$2 $1") + " " + "aaa".replaceAll("a*", "X") + " " + "".replaceAll("", "X"));
        System.out.println("abc".replace("", "-") + " " + "ab".replace("b", "$1") + " " + "a\\b".replaceAll("\\\\", "/"));
        System.out.println("done");
    }
}
