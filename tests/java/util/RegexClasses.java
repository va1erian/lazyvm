package util;

import java.util.regex.Pattern;

/** Character classes: ranges, union, intersection, negation, predefined and property classes. */
public class RegexClasses {
    /** The code points in [0, 0x100) that the pattern matches as a whole, as compact ranges. */
    static void set(String re) {
        Pattern p = Pattern.compile(re);
        StringBuilder sb = new StringBuilder(re + " =>");
        int start = -1;
        for (int c = 0; c <= 0x100; c++) {
            boolean in = c < 0x100 && p.matcher(new String(Character.toChars(c))).matches();
            if (in && start < 0) {
                start = c;
            } else if (!in && start >= 0) {
                sb.append(' ').append(Integer.toHexString(start));
                if (c - 1 > start) {
                    sb.append('-').append(Integer.toHexString(c - 1));
                }
                start = -1;
            }
        }
        System.out.println(sb);
    }

    static void test(String re, String... inputs) {
        Pattern p = Pattern.compile(re);
        StringBuilder sb = new StringBuilder(re + " :");
        for (String s : inputs) {
            sb.append(' ').append(p.matcher(s).matches());
        }
        System.out.println(sb);
    }

    public static void main(String[] args) {
        set("[abc]");
        set("[a-c]");
        set("[^a-y]");
        set("[a-cx-z]");
        set("[a-c[x-z]]");
        set("[a-z&&[def]]");
        set("[a-z&&[^aeiou]]");
        set("[a-d&&b-f&&c-z]");
        set("[^a-c&&b-d]");
        set("[^a[b]c]");
        set("[a[^b]c]");
        set("[\\w&&[^\\d_]]");
        set("[-a]");
        set("[a-]");
        set("[a-b-c]");
        set("[]a]");
        set("[^]a]");
        set("[a^]");
        set("[\\^\\-\\]]");
        set("[\\Q^-]\\E]");
        set("[&&a]");
        set("[a&&]");
        set("[a&&&b]");
        set("[\\x41-\\x43\\u0061\\0142]");
        set("\\d");
        set("\\D");
        set("\\w");
        set("\\W");
        set("\\s");
        set("\\S");
        set("\\h");
        set("\\H");
        set("\\v");
        set("\\V");
        set(".");
        set("(?s).");
        set("\\p{Lower}");
        set("\\p{Upper}");
        set("\\p{ASCII}");
        set("\\p{Alpha}");
        set("\\p{Digit}");
        set("\\p{Alnum}");
        set("\\p{Punct}");
        set("\\p{Graph}");
        set("\\p{Print}");
        set("\\p{Blank}");
        set("\\p{Cntrl}");
        set("\\p{XDigit}");
        set("\\p{Space}");
        set("\\P{Alpha}");
        set("[\\p{Lower}&&[^a-c]]");
        set("\\p{L}");
        set("\\p{Lu}");
        set("\\p{Ll}");
        set("\\p{N}");
        set("\\p{Nd}");
        set("\\p{P}");
        set("\\p{S}");
        set("\\p{Z}");
        set("\\p{C}");
        set("\\p{Sc}");
        set("\\p{Pd}");
        set("\\p{Sm}");
        set("\\p{IsAlphabetic}");
        set("\\p{javaLowerCase}");
        set("\\p{javaUpperCase}");
        set("\\p{javaWhitespace}");
        set("\\p{javaLetter}");
        set("\\p{javaDigit}");
        set("\\p{javaLetterOrDigit}");
        set("\\p{javaISOControl}");
        set("\\p{IsLatin}");
        set("\\p{InBasicLatin}");
        set("\\p{InLatin-1Supplement}");
        set("\\p{gc=Lu}");
        set("\\p{general_category=Nd}");
        set("\\p{IsL}");
        set("\\pL");
        set("\\PL");
        test("\\p{IsGreek}+", "αβ", "a");
        test("\\p{InGreek}+", "αβ", "a");
        test("\\p{Lu}+", "ΑΒ", "α");
        test("\\p{Ll}+", "αβ", "Α");
        test("\\p{IsCyrillic}+", "абЖ", "a");
        test("\\p{L}+", "абЖabc", "а ");
        test("\\p{IsHan}", "中", "a");
        test("\\p{IsHiragana}", "あ", "ア");
        test("\\R", "\n", "\r\n", "\r", " ", "\u0085", "a", "\n\n");
        test("\\R\\R", "\r\n\n", "\n\r", "\r\n\r\n");
        test("a\\Rb", "a\r\nb", "a\nb", "ab");
        test("[\\d&&[^5]]+", "01234", "0125");
        test("[a-c&&[^b]]+", "ac", "abc");
        test("[a-cA-C]+", "abCA", "abd");
        test("[^abc]", "d", "a");
        test("[\\s\\S]", "\n", "x");
        test("[\\x{1F600}-\\x{1F64F}]", "😀", "a");
        test(".", "😀", "\ud83d", "ab");
        test("..", "😀", "ab");
        test("[^a]", "😀", "a");
        System.out.println("done");
    }
}
