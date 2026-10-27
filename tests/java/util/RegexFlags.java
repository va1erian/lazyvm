package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pattern flags, set at compile time and inline, and what each changes. */
public class RegexFlags {
    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 32 || c > 126) {
                sb.append("\\u").append(Integer.toHexString(c | 0x10000).substring(1));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static void find(String re, int flags, String in) {
        Pattern p;
        try {
            p = Pattern.compile(re, flags);
        } catch (PatternSyntaxException e) {
            System.out.println(esc(re) + " f=" + flags + " : " + e.getDescription() + " @" + e.getIndex());
            return;
        }
        Matcher m = p.matcher(in);
        StringBuilder sb = new StringBuilder(esc(re) + " f=" + flags + "(" + p.flags() + ") " + esc(in) + " :");
        while (m.find()) {
            sb.append(" [").append(m.start()).append(',').append(m.end()).append(')');
        }
        System.out.println(sb + " matches=" + p.matcher(in).matches());
    }

    public static void main(String[] args) {
        int i = Pattern.CASE_INSENSITIVE;
        System.out.println(Pattern.UNIX_LINES + " " + Pattern.CASE_INSENSITIVE + " " + Pattern.COMMENTS + " " + Pattern.MULTILINE + " " + Pattern.LITERAL
            + " " + Pattern.DOTALL + " " + Pattern.UNICODE_CASE + " " + Pattern.CANON_EQ + " " + Pattern.UNICODE_CHARACTER_CLASS);
        find("abc", 0, "ABC abc");
        find("abc", i, "ABC aBc abd");
        find("[a-c]+", i, "AbCd");
        find("[^a-c]+", i, "AbCd");
        find("\\p{Lower}+", i, "abC");
        find("(a)\\1", i, "aA");
        find("é", i, "Éé");
        find("é", i | Pattern.UNICODE_CASE, "Éé");
        find("[à-å]", i | Pattern.UNICODE_CASE, "Àã");
        find("σ", i | Pattern.UNICODE_CASE, "Σσς");
        find("бв", i | Pattern.UNICODE_CASE, "БВбв");
        find("бв", i, "БВ");
        find("k", i | Pattern.UNICODE_CASE, "KK");
        find("k", i, "KK");
        find("(?i)abc", 0, "ABC");
        find("(?iu)é", 0, "É");
        find("a(?i)b", 0, "aB Ab");
        find("(?i:a)b", 0, "Ab AB");
        find("a.b", 0, "a\nb a\rb a\u0085b a b a b axb");
        find("a.b", Pattern.DOTALL, "a\nb a\rb");
        find("a.b", Pattern.UNIX_LINES, "a\nb a\rb a\u0085b");
        find("(?s)a.b", 0, "a\nb");
        find("(?d)a.b", 0, "a\nb a\rb");
        find("^a", 0, "a\na");
        find("^a", Pattern.MULTILINE, "a\na\r\na\ra");
        find("a$", Pattern.MULTILINE, "a\na\r\na\ra");
        find("a$", 0, "a\n");
        find("a$", 0, "a\r\n");
        find("a$", 0, "a\n\n");
        find("a$", Pattern.UNIX_LINES, "a\r");
        find("a$", Pattern.MULTILINE | Pattern.UNIX_LINES, "a\ra\n");
        find("^$", Pattern.MULTILINE, "\n\na");
        find("^", Pattern.MULTILINE, "a\n");
        find("$", Pattern.MULTILINE, "a\n");
        find("(?m)^a$", 0, "x\na\ny");
        find("a b # not part\n c", Pattern.COMMENTS, "abc");
        find("a\\ b", Pattern.COMMENTS, "a b");
        find("a[ ]b", Pattern.COMMENTS, "a b");
        find("[a b]+", Pattern.COMMENTS, "a b");
        find("\\# a", Pattern.COMMENTS, "#a");
        find("a # comment", Pattern.COMMENTS, "a");
        find("a b", 0, "a b ab");
        find("(?x) a b ", 0, "ab");
        find("a. b", Pattern.LITERAL, "xa. by");
        find("(a|b)", Pattern.LITERAL, "(a|b) a");
        find("\\d", Pattern.LITERAL, "\\d 5");
        find("\\Qa\\E", Pattern.LITERAL, "\\Qa\\E a");
        find("A.B", Pattern.LITERAL | Pattern.CASE_INSENSITIVE, "a.b axb");
        find("", Pattern.LITERAL, "ab");
        find("é", Pattern.CANON_EQ, "é");
        find("\\w+", 0, "café naïve");
        find("\\w+", Pattern.UNICODE_CHARACTER_CLASS, "café naïve");
        find("\\b\\w", Pattern.UNICODE_CHARACTER_CLASS, "éa b");
        find("(?U)\\w+", 0, "café");
        find("\\p{Alpha}+", 0, "café");
        find("\\p{Alpha}+", Pattern.UNICODE_CHARACTER_CLASS, "café");
        find("\\p{Lower}+", Pattern.UNICODE_CHARACTER_CLASS, "caféX");
        find("\\p{Upper}+", Pattern.UNICODE_CHARACTER_CLASS, "ÉXé");
        find("\\s+", Pattern.UNICODE_CHARACTER_CLASS, "a  b");
        find("\\s+", 0, "a  b");
        find("\\d+", Pattern.UNICODE_CHARACTER_CLASS, "12");
        find("[\\w&&[^\\d]]+", Pattern.UNICODE_CHARACTER_CLASS, "café1");
        find("é", Pattern.UNICODE_CHARACTER_CLASS, "É");
        find("a", i | Pattern.MULTILINE | Pattern.DOTALL | Pattern.COMMENTS | Pattern.UNIX_LINES, "A");
        find("(?idmsux)a", 0, "A");
        find("(?idmsux-idmsux)a", 0, "a");
        find("(?i)a(?-i)b(?i)c", 0, "AbC ABC");
        find("((?i)a)b", 0, "Ab AB");
        find("(?i)(?-i:a)b", 0, "aB AB");
        find("(?m)(?s)^a.b$", 0, "x\na\nb\ny");
        find("(?i)", 0, "x");
        find("(?-)", 0, "x");
        find("(?i-)a", 0, "A");
        System.out.println(Pattern.compile("(?i)a").flags() + " " + Pattern.compile("(?i)a", Pattern.DOTALL).flags() + " "
            + Pattern.compile("a(?m)").flags() + " " + Pattern.compile("(a(?m))").flags() + " " + Pattern.compile("(?:a)(?s)").flags());
        System.out.println(Pattern.compile("a", 0xFFFF).flags() + " " + Pattern.compile("a", 512).flags());
        for (String re : new String[] {"( ?:a)b", "(? :a)b", "(?  =a)b", "(?< n>a)", "(?<n >a)", "(?i )a", "( ?=a)a", "( ? i)a", "(?i: a)"}) {
            find(re, Pattern.COMMENTS, "ab");
        }
        find("( ?:a)b", 0, "ab");
        // Under COMMENTS, white space and comments are skipped on either side of the '-' of a range.
        for (String re : new String[] {"[a- b]", "[a - b]", "[a -b]", "[a-\n b]", "[a-#c\nb]", "[\\r- ]", "[a- ]", "[ a-b]", "[a-b ]c",
            "[a-b#x\n]", "[a - ]", "[^a- b]", "[a-\\n b]", "[a #\n- b]", "[a-b- c]"}) {
            find(re, Pattern.COMMENTS, "abc \n-");
        }
        find("[a- b]", 0, "abc \n-");
        wordBoundaries();
        System.out.println("done");
    }

    /** \b with combining marks: by default a mark joins the word only after a letter or digit. */
    static void wordBoundaries() {
        String[] texts = {"_́", "á", "1́", "́", "́a", "á̂", "_́̂", "aः", "a⃝", "a‍", "áb", " ́ a", "a𐇽", "𐐀́", "é_"};
        for (String t : texts) {
            StringBuilder sb = new StringBuilder("\\b in " + esc(t) + ":");
            for (int flags : new int[] {0, Pattern.UNICODE_CHARACTER_CLASS}) {
                Matcher m = Pattern.compile("\\b", flags).matcher(t);
                sb.append(flags == 0 ? " default" : "; unicode");
                while (m.find()) {
                    sb.append(' ').append(m.start());
                }
                Matcher n = Pattern.compile("\\B", flags).matcher(t);
                sb.append(" /");
                while (n.find()) {
                    sb.append(' ').append(n.start());
                }
            }
            System.out.println(sb);
        }
    }
}
