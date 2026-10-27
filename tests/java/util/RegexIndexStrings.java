package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The cached lookbehind index of RegexIndexReset, when the text changes with no reset at all and
 * when one String matcher is moved to other Strings. Split from RegexIndexReset to keep each test
 * within the runner's time limit.
 */
public class RegexIndexStrings {
    static String rep(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    static void show(String tag, Matcher m) {
        StringBuilder o = new StringBuilder(tag + ":");
        int n = 0;
        while (n++ < 5 && m.find()) {
            o.append(' ').append(m.start()).append('-').append(m.end());
        }
        System.out.println(o);
    }

    public static void main(String[] args) {
        for (String re : new String[] {"(?<=a[xb]{0,2100})b", "(?<=a[xb]{0,2100}+)b"}) {
            // No reset at all: the next find() sees a text of another length.
            StringBuilder sb = new StringBuilder(rep('x', 2100) + "ab");
            Matcher m = Pattern.compile(re).matcher(sb);
            StringBuilder o = new StringBuilder(re + " no reset:");
            boolean first = m.find();
            o.append(' ').append(first ? m.start() : -1);
            sb.setLength(0);
            sb.append(rep('x', 2600)).append("abb");
            while (m.find()) {
                o.append(' ').append(m.start()).append('-').append(m.end());
            }
            System.out.println(o);
        }
        // The same String matcher moved to other Strings keeps no index of the old one.
        for (String re : new String[] {"(?<=a[xb]{0,2100})b", "(?<=a[xb]{0,2100}+)b"}) {
            Matcher m = Pattern.compile(re).matcher(rep('x', 2100) + "ab");
            show(re + " string first", m);
            m.reset(rep('x', 2600) + "ab");
            show(re + " string longer", m);
            m.reset(rep('x', 2600) + "ba");
            show(re + " string none", m);
            m.reset(rep('x', 100) + "ab");
            show(re + " string shorter", m);
        }
        System.out.println("done");
    }
}
