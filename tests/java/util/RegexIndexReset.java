package util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lookbehind over a long way back (more than 2048 chars) caches an index of the starts it can
 * use. The text may be changed between searches when the matcher is reset, with reset() or with
 * reset(input), so the index must not outlive that.
 */
public class RegexIndexReset {
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
        for (String re : new String[] {"(?<=a[xb]{0,3000})b", "b(?<=a[xb]{0,3000})", "(?<=a[xb]{0,3000}+)b"}) {
            for (boolean viaInput : new boolean[] {false, true}) {
                String name = re + (viaInput ? " reset(input)" : " reset()");
                StringBuilder sb = new StringBuilder(rep('x', 2100) + "ab");
                Matcher m = Pattern.compile(re).matcher(sb);
                show(name + " first", m);
                sb.setLength(0);
                sb.append(rep('x', 4000)).append("ab");
                if (viaInput) {
                    m.reset(sb);
                } else {
                    m.reset();
                }
                show(name + " longer", m);
                sb.setCharAt(4000, 'x');
                sb.setCharAt(3999, 'a');
                if (viaInput) {
                    m.reset(sb);
                } else {
                    m.reset();
                }
                show(name + " same length", m);
                sb.setLength(0);
                sb.append(rep('x', 100)).append("ab");
                if (viaInput) {
                    m.reset(sb);
                } else {
                    m.reset();
                }
                show(name + " shorter", m);
            }
        }
        for (String re : new String[] {"(?<=a[xb]{0,3000})b", "(?<=a[xb]{0,3000}+)b"}) {
            // No reset at all: the next find() sees a text of another length.
            StringBuilder sb = new StringBuilder(rep('x', 2100) + "ab");
            Matcher m = Pattern.compile(re).matcher(sb);
            StringBuilder o = new StringBuilder(re + " no reset:");
            boolean first = m.find();
            o.append(' ').append(first ? m.start() : -1);
            sb.setLength(0);
            sb.append(rep('x', 4000)).append("abb");
            while (m.find()) {
                o.append(' ').append(m.start()).append('-').append(m.end());
            }
            System.out.println(o);
        }
        System.out.println("done");
    }
}
