package util;

import java.util.regex.Pattern;

/** String.split and Pattern.split: empty pieces, limits, zero-width matches, fast-path look-alikes. */
public class RegexSplit {
    /** Escapes line breaks and non-ASCII chars so the transcript is plain ASCII. */
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

    static String fmt(String[] parts) {
        StringBuilder sb = new StringBuilder("(" + parts.length + ")");
        for (String p : parts) {
            sb.append(" [").append(esc(p)).append(']');
        }
        return sb.toString();
    }

    static void split(String in, String re) {
        System.out.println("'" + esc(in) + "'.split(" + esc(re) + ") " + fmt(in.split(re)));
        System.out.println("   pattern " + fmt(Pattern.compile(re).split(in)));
    }

    static void limit(String in, String re, int limit) {
        System.out.println("'" + in + "'.split(" + re + ", " + limit + ") " + fmt(in.split(re, limit)));
        String[] viaPattern = Pattern.compile(re).split(in, limit);
        if (!fmt(viaPattern).equals(fmt(in.split(re, limit)))) {
            System.out.println("   MISMATCH " + fmt(viaPattern));
        }
    }

    public static void main(String[] args) {
        split("a,b,c", ",");
        split("a,b,c,,", ",");
        split(",a,b", ",");
        split(",,a,,b,,", ",");
        split("", ",");
        split(",", ",");
        split(",,,", ",");
        split("abc", ",");
        split("abc", "");
        split("a b  c", " ");
        split("a b  c", " +");
        split("a b  c", "\\s");
        split("  a b", " +");
        split("a1b22c", "\\d");
        split("a1b22c", "\\d+");
        split("a1b22c", "(\\d)");
        split("a1b22c", "(?<=\\d)");
        split("a1b22c", "(?=\\d)");
        split("a1b22c", "(?<=\\d)(?=\\D)");
        split("abc", "(?=b)");
        split("abc", "x*");
        split("abc", "b*");
        split("aXbxc", "(?i)x");
        split("a.b.c", ".");
        split("a.b.c", "\\.");
        split("a.b.c", "[.]");
        split("a|b|c", "\\|");
        split("a|b|c", "|");
        split("a\\b\\c", "\\\\");
        split("a$b", "$");
        split("a$b", "\\$");
        split("line1\nline2\r\nline3", "\\R");
        split("line1\nline2\n", "\n");
        split("aébéc", "é");
        split("a😀b", "");
        split("a😀b", "😀");
        split("a::b", ":");
        split("a::b", "::");
        split("aXXb", "X");
        split("aXXb", "XX");
        split("boo:and:foo", "o");
        split("boo:and:foo", ":");
        split("boo:and:foo", "o+");
        split("^a^b", "\\^");
        split("1+2+3", "\\+");
        split("tab\tsep", "\t");
        split("x", "x");
        split("xx", "x");
        split("axb", "x?");
        limit("boo:and:foo", ":", 2);
        limit("boo:and:foo", ":", 5);
        limit("boo:and:foo", ":", -2);
        limit("boo:and:foo", "o", 5);
        limit("boo:and:foo", "o", -2);
        limit("boo:and:foo", "o", 0);
        limit("a,b,c,,", ",", -1);
        limit("a,b,c,,", ",", 0);
        limit("a,b,c,,", ",", 3);
        limit("a,b,c,,", ",", 4);
        limit("a,b,c,,", ",", 5);
        limit("a,b,c,,", ",", 6);
        limit("a,b,c,,", ",", 7);
        limit(",a", ",", 1);
        limit(",a", ",", 2);
        limit("", ",", 1);
        limit("", ",", -1);
        limit("abc", "", 2);
        limit("abc", "", -1);
        limit("abc", "", 3);
        limit("abc", "", 4);
        limit("abc", "", 10);
        limit("abc", "(?=b)", 1);
        limit("abc", "", 1);
        limit(",,", ",", 1);
        limit(",,", ",", 2);
        limit(",,", ",", 3);
        limit(",,", ",", -1);
        try {
            "a".split("(");
        } catch (RuntimeException e) {
            System.out.println(e.getClass().getName());
        }
        try {
            Pattern.compile(",").split(null);
        } catch (NullPointerException e) {
            System.out.println("NPE");
        }
        System.out.println(fmt(Pattern.compile(",").split(new StringBuilder("a,b,,"))));
        System.out.println(fmt(Pattern.compile(",").split(new StringBuilder("a,b,,"), -1)));
        System.out.println("done");
    }
}
