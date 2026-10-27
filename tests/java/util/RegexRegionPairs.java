package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regions whose ends split a surrogate pair, with case folding and with a pattern that holds a raw
 * surrogate (which makes the search step over whole pairs), plus the last letter of the ASCII
 * alphabet under CASE_INSENSITIVE.
 */
public class RegexRegionPairs {
    static void find(String p, int flags, String t, int lo, int hi) {
        for (int b = 0; b < 2; b++) {
            StringBuilder o = new StringBuilder(p.length() + " tb=" + b + " :");
            Matcher m = Pattern.compile(p, flags).matcher(t);
            m.region(lo, hi).useTransparentBounds(b == 1);
            while (m.find()) {
                o.append(" [").append(m.start()).append(',').append(m.end()).append(']');
            }
            o.append(m.hitEnd() ? " hitEnd" : " no hitEnd");
            // lookingAt stops at the first failure, so hitEnd comes only from where it failed.
            Matcher at = Pattern.compile(p, flags).matcher(t);
            at.region(lo, hi).useTransparentBounds(b == 1);
            o.append(at.lookingAt() ? " looks" : " no look").append(at.hitEnd() ? " hit" : " nohit");
            System.out.println(o);
        }
    }

    static void folded() {
        int ci = Pattern.CASE_INSENSITIVE;
        String e = "\ud83d\ude00";
        String t = e + "\u01c6" + e + "\u01c6\u1fbe\u01c6\u01c6\u01c6\u01c6\u01c6" + e;
        int hi = t.length() - 1;
        String[] ps = {"[^\\p{ASCII}" + e + "]\u01c6\\x{1f600}", "[^\\p{ASCII}" + e + "]\u01c6" + e, "\u01c6\\x{1f600}",
            "[^a]\u01c6\\x{1f600}", "\u01c6\u01c6\\x{1f600}", ".\\x{1f600}", "\\x{1f600}", "[^\\p{ASCII}]\u01c6\\x{1f600}",
            "\\W\u01c6\\x{1f600}"};
        for (String p : ps) {
            find(p, ci, t, 1, hi);
        }
        String u = "\u01c6\u01c6" + e;
        String[] qs = {"[^\\p{ASCII}" + e + "]\u01c6\\x{1f600}", "[^b" + e + "]\u01c6\\x{1f600}", "[^b\\x{1f600}]\u01c6\\x{1f600}",
            "[\u01c6" + e + "]\u01c6\\x{1f600}", "[\u01c6]\u01c6\\x{1f600}"};
        for (String p : qs) {
            find(p, ci, u, 0, 3);
        }
    }

    static void stepping() {
        String t = "\ud801\udc00\ud801\udc28\ud801\udc00\ud801\udc28";
        String hi = "\ud801\udc28";
        String[] ps = {"$|(([\u017f\\x{10400}-" + hi + "\u017f])?\u017f\\p{javaLowerCase})^(?U:\\P{Lu}\u03c2\u1e61)?",
            "$|(([\\x{10400}-" + hi + "])?\u017f\\p{javaLowerCase})^", "$|[\\x{10400}-" + hi + "]?a^", "$|[\\x{10400}-" + hi + "]a",
            "$|a", "$|\\x{10400}?a", "$|\\x{10400}?a^", "$|(?:\\x{10400}c)^", "$"};
        for (String p : ps) {
            find(p, Pattern.CASE_INSENSITIVE, t, 1, 7);
            find(p, 0, t, 1, 7);
        }
    }

    static void ascii() {
        String[] ps = {"z", "Z", "[a-z]", "[^a-y]", "\\x7a", "[Z]", "(?:y|z)+", "\\p{Lower}", "[\\p{Upper}]"};
        String[] ts = {"z", "Z", "zZzZ", "y", "[`{@", "z{"};
        for (String p : ps) {
            StringBuilder sb = new StringBuilder(p + ":");
            for (String t : ts) {
                sb.append(' ').append(Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(t).matches());
                sb.append(Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(t).find());
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) {
        folded();
        stepping();
        ascii();
        System.out.println("done");
    }
}
