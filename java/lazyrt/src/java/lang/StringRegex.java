package java.lang;

import java.util.ArrayList;
import java.util.List;
import lazy.internal.Regex;

/** String's regular expression methods, on lazy.internal.Regex until java.util.regex (S5-03). */
final class StringRegex {
    private StringRegex() {
    }

    static boolean matches(String s, String regex) {
        return Regex.compile(regex, false).matches(s);
    }

    /** Where the search goes after a match: past it, or one further after an empty one. */
    private static int after(int[] m) {
        return m[1] == m[0] ? m[1] + 1 : m[1];
    }

    // Javadoc of String.split: a zero-width match at the beginning gives no leading empty string;
    // limit 0 drops trailing empty strings.
    static String[] split(String s, String regex, int limit) {
        Regex re = Regex.compile(regex, false);
        List<String> parts = new ArrayList<>();
        int from = 0;
        int at = 0;
        while (at <= s.length() && (limit <= 0 || parts.size() < limit - 1)) {
            int[] m = re.find(s, at);
            if (m == null) {
                break;
            }
            if (m[1] != 0) {
                parts.add(s.substring(from, m[0]));
                from = m[1];
            }
            at = after(m);
        }
        if (parts.isEmpty()) {
            return new String[] {s};
        }
        parts.add(s.substring(from));
        int n = parts.size();
        while (limit == 0 && n > 0 && parts.get(n - 1).isEmpty()) {
            n--;
        }
        return parts.subList(0, n).toArray(new String[n]);
    }

    static String replace(String s, String regex, String replacement, boolean all) {
        Regex re = Regex.compile(regex, false);
        StringBuilder sb = new StringBuilder();
        int from = 0;
        int at = 0;
        while (at <= s.length()) {
            int[] m = re.find(s, at);
            if (m == null) {
                break;
            }
            sb.append(s, from, m[0]);
            substitute(sb, replacement, s, m, re.groupCount());
            from = m[1];
            at = after(m);
            if (!all) {
                break;
            }
        }
        return sb.append(s, from, s.length()).toString();
    }

    /** Appends `rep` with `$n` group references and `\` escapes resolved (Matcher.appendReplacement). */
    private static void substitute(StringBuilder sb, String rep, String s, int[] m, int groups) {
        for (int i = 0; i < rep.length(); i++) {
            char c = rep.charAt(i);
            if (c == '\\') {
                if (++i >= rep.length()) {
                    throw new IllegalArgumentException("character to be escaped is missing");
                }
                sb.append(rep.charAt(i));
            } else if (c == '$') {
                if (++i >= rep.length() || !Character.isDigit(rep.charAt(i))) {
                    throw new IllegalArgumentException("Illegal group reference");
                }
                // The longest run of digits that still names an existing group.
                int g = rep.charAt(i) - '0';
                while (i + 1 < rep.length() && Character.isDigit(rep.charAt(i + 1))
                        && g * 10 + (rep.charAt(i + 1) - '0') <= groups) {
                    g = g * 10 + (rep.charAt(++i) - '0');
                }
                if (g > groups) {
                    throw new IndexOutOfBoundsException("No group " + g);
                }
                if (m[2 * g] >= 0) {
                    sb.append(s, m[2 * g], m[2 * g + 1]);
                }
            } else {
                sb.append(c);
            }
        }
    }
}
