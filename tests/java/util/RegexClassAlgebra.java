package util;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bracketed classes with && and nested classes, over the alphabet "abcdx&1-^". The rows are the
 * cases that tell the rules apart; the exhaustive part runs every class of up to four items and
 * prints a count and a hash of the results, so a difference anywhere shows even when no row does.
 */
public class RegexClassAlgebra {
    static final String ALPHABET = "abcdx&1-^éÿĀ";

    static String members(String cls) {
        StringBuilder sb = new StringBuilder();
        try {
            Pattern p = Pattern.compile(cls);
            for (int i = 0; i < ALPHABET.length(); i++) {
                if (p.matcher(String.valueOf(ALPHABET.charAt(i))).matches()) {
                    sb.append(ALPHABET.charAt(i));
                }
            }
        } catch (PatternSyntaxException e) {
            sb.append("error ").append(e.getDescription()).append(" @").append(e.getIndex());
        }
        return sb.toString();
    }

    static void rows(String title, String... classes) {
        System.out.println("== " + title);
        for (String c : classes) {
            System.out.println(c + " -> {" + members(c) + "}");
        }
    }

    /** The hex code points among the line breaks, white space and a few letters that cls matches. */
    static String hexMembers(String cls) {
        String alphabet = "\n\u000b\f\r\u0085  -az0\t_b";
        StringBuilder sb = new StringBuilder();
        try {
            Pattern p = Pattern.compile(cls);
            for (int i = 0; i < alphabet.length(); i++) {
                if (p.matcher(String.valueOf(alphabet.charAt(i))).matches()) {
                    sb.append(' ').append(Integer.toHexString(alphabet.charAt(i)));
                }
            }
        } catch (PatternSyntaxException e) {
            sb.append(" error ").append(e.getDescription()).append(" @").append(e.getIndex());
        }
        return sb.toString();
    }

    static final String[] TOKENS = {"a", "b", "[b]", "[^b]", "a-c", "\\d", "&&", "&"};
    static long hash;
    static int count;
    static int distinct;

    static void sweep(String body, int depth) {
        if (body.length() > 0 && !body.equals("^")) {
            String result = members("[" + body + "]");
            hash = hash * 31 + (body + "=" + result).hashCode();
            count++;
            if (result.length() > 0) {
                distinct++;
            }
        }
        if (depth > 0) {
            for (String t : TOKENS) {
                sweep(body + t, depth - 1);
            }
        }
    }

    public static void main(String[] args) {
        rows("an empty right side keeps the last item, and a run of plain characters is one item",
            "[a&&]", "[ab&&]", "[a-c&&]", "[a[b]&&]", "[ab-d&&]", "[a-ca&&]", "[a\\d&&]", "[a[bc]b&&]", "[a[^b]b&&]", "[b[^b]&&]",
            "[ab-db&&]", "[abc&&]", "[a&a&&]");
        rows("negated, the same",
            "[^a&&]", "[^ab&&]", "[^\\d&&]", "[^a[b]&&]", "[^a[^b]&&]", "[^a-c&&]", "[^aa-c&&]", "[^a[a&&b]&&]");
        rows("a right side starting with a plain item replaces the nested classes before it",
            "[a-z&&[b]c]", "[a-z&&[^b]x]", "[a-z&&c[b]]", "[a-z&&[b][c]]", "[a-z&&[b]cd]", "[a-d&&[bc]a-c]", "[a-d&&[b]&&c]", "[a-d&&bc&&cd]");
        rows("&, && and & again",
            "[^a&&&b]", "[^a&&b&c]", "[a&&&b]", "[a&&[b]&]", "[c&&[b]&]", "[[b]&&[^b]&]", "[a[b]&&&]", "[ab&&&]", "[a&&&&]", "[a&&&]");
        rows("negation after &&",
            "[^a&&[bc]&d]", "[^a&&[bc]&&d]", "[^ab&&[bcd]&c]", "[^a&&[b]&c]", "[^a&&[b]&&c&&d]", "[^a[b]&c]", "[^a[b]&&[c]&a]");
        rows("negation applies to each item written directly, not to nested classes",
            "[^a[b]]", "[^a[b]c]", "[^ab[b]a]", "[^aa[^b]b]", "[^a-c&&b-d]", "[^ab[bc]c]", "[^a[b][c]]");
        rows("only characters below U+0100 join that run; a later one from U+0100 up is an item of its own",
            "[aĀ&&]", "[Āa&&]", "[aÿ&&]", "[ÿa&&]", "[éĀ&&]", "[aé&&]", "[^bĀ[b]Ā]", "[^bÿ[b]ÿ]",
            "[^bĀ[b]c]", "[^ĀĀ[^b]b]");
        rows("errors",
            "[&&]", "[&&a]", "[a&&", "[a&&[", "[]", "[a-", "[b-a]", "[a&&b-a]");
        System.out.println("== \\v before a '-' is the one character U+000B");
        for (String c : new String[] {"[\\v]", "[\\v-]", "[\\v-z]", "[a\\v-]", "[\\v-\\n]", "[\\v-\\x0c]", "[-\\v]", "[\\v-a-c]", "[\\v-\\v]", "[a-\\v]",
            "[\\v-[a]]", "[\\v-&&[b]]", "[\\h-]", "[\\h-z]", "[\\V-]", "[\\w-z]", "[\\s-z]", "[\\v\\v-]", "[\\v -]"}) {
            System.out.println(c + " ->" + hexMembers(c));
        }
        System.out.println("== every class of up to four items");
        sweep("", 4);
        long plain = hash;
        System.out.println(count + " classes, " + distinct + " not empty, hash " + plain);
        count = 0;
        distinct = 0;
        hash = 0;
        sweep("^", 4);
        System.out.println(count + " negated classes, " + distinct + " not empty, hash " + hash);
        System.out.println("done");
    }
}
