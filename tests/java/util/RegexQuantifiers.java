package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Greedy, reluctant and possessive quantifiers, counted repeats, zero-width loops, backtracking. */
public class RegexQuantifiers {
    static void all(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        StringBuilder sb = new StringBuilder(re + " in '" + in + "':");
        int n = 0;
        while (n++ < 12 && m.find()) {
            sb.append(" [").append(m.start()).append(',').append(m.end()).append(')');
            for (int g = 1; g <= m.groupCount(); g++) {
                sb.append(' ').append(m.group(g) == null ? "null" : "'" + m.group(g) + "'");
            }
        }
        System.out.println(sb);
    }

    static void full(String re, String in) {
        Matcher m = Pattern.compile(re).matcher(in);
        boolean ok = m.matches();
        String shown = in.length() > 30 ? "<" + in.length() + " chars>" : in;
        System.out.println(re + " matches '" + shown + "': " + ok + (ok && m.groupCount() > 0 ? " " + m.group(1) : ""));
    }

    static String rep(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        String[] quants = {"*", "+", "?", "{2}", "{2,}", "{1,2}", "{0}", "{0,1}", "*?", "+?", "??", "{2,}?", "{1,2}?", "*+", "++", "?+", "{1,2}+"};
        for (String q : quants) {
            all("a" + q, "caaaab");
            all("(?:ab)" + q, "xababab");
        }
        all("a*b", "aaab b ab");
        all("a*?b", "aaab");
        all("a*+b", "aaab");
        all(".*foo", "xfooyfoo");
        all(".*?foo", "xfooyfoo");
        all(".*+foo", "xfooyfoo");
        all("<.+>", "<a><b>");
        all("<.+?>", "<a><b>");
        all("<[^>]+>", "<a><b>");
        all("(a+)(b+)", "aabbb");
        all("(a+?)(b+?)", "aabbb");
        all("(a++)(b++)", "aabbb");
        all("(a*)*", "aab");
        all("(a*)+", "aab");
        all("(a?)*", "aab");
        all("(a|b)*c", "abbac");
        all("(a|b)*?c", "abbac");
        all("(a|ab)*c", "abac");
        all("(a*?)*", "aa");
        all("(?:a*)*", "aaa");
        all("(?:a|())*b", "aab");
        all("(?:\\b)*x", "x");
        all("(?:^)*x", "x");
        all("(\\b)?x", "x");
        all("(\\b)*x", "x");
        all("()*x", "x");
        all("(){0}x", "x");
        all("(a){0}b", "ab");
        all("(a){0,0}b", "ab");
        all("(a{0})*x", "x");
        all("(a){2}", "aaaaa");
        all("(a){2,3}", "aaaaa");
        all("(ab){2}", "ababab");
        all("(\\d{1,2}){2}", "1234");
        all("(\\d{1,2}?){2}", "1234");
        all("x{2}{3}", "xxxxxx x{3}");
        all("a{1}{2}", "a a{2}");
        all("{1}a", "a");
        all("(?:x{2}){3}", "xxxxxx");
        all("a{2,3}?", "aaaa");
        all("a{0,2}?b", "aab");
        all("\\w+?(?=\\d)", "abc123");
        all("\\d+?", "123");
        all("\\d*+\\d", "123");
        all("(?>\\d*)\\d", "123");
        all("[ab]*?b", "aabab");
        all("[ab]*+b", "aabab");
        all("[ab]++b", "aabab");
        all("\\s*\\w+\\s*", "  ab  cd ");
        all("(\\w+\\s*)+", "one two");
        all("(\\w+\\s*)++", "one two");
        all("(?:(\\w+)\\s*)+", "one two");
        all("\\.*?", "...");
        all("a*", "😀aa");
        all(".*", "a😀b");
        all(".+?", "😀😀");
        all("[^a]*", "😀a");
        full("a{3}", "aaa");
        full("a{3}", "aaaa");
        full("(a|b){3}", "aba");
        full("(a|b){2,3}", "ababab");
        full("(a|b){2,3}", "ab");
        full("(a*)*b", "aaab");
        full("(a|aa)+b", "aaaab");
        full("(a+)+b", "aaaaaaaaaaaaaaab");
        full("(a+)+b", "aaaaaaaaaaaaaaac");
        // Linear time: possessive and atomic forms never back off.
        String big = rep("a", 1000);
        full("a*", big);
        full("a*+", big);
        full("(?>a*)", big);
        full("(?:a|b)*", big);
        full("[ab]*c", big + "c");
        full(".*c", big);
        full("(?>a+)b", big);
        full("a*+a", big);
        System.out.println(Pattern.compile("a*").matcher(big).replaceAll("x"));
        System.out.println("done");
    }
}
