package java.nio.file;

import java.util.regex.PatternSyntaxException;

/**
 * Glob to regular expression for getPathMatcher (Javadoc of FileSystem.getPathMatcher: `*` and
 * `?` stay within a name, `**` crosses names, `[...]` and `[!...]` classes, `{a,b}` groups that
 * do not nest, `\` escapes). The output has the oracle's shape, so a class the glob passes but
 * the regular expression refuses fails the same way (nio-file.md P4): `^...$`, `?` as one
 * non-separator char, and a class as the intersection of "not the separator" with its set.
 */
final class Globs {
    /** Chars a regular expression gives a meaning outside a class (observed: `}` is not escaped). */
    private static final String META = "\\^$.|?*+()[]{";

    private Globs() {
    }

    static String toRegex(String glob, char sep) {
        String notSep = sep == '\\' ? "[^\\\\]" : "[^/]";
        StringBuilder sb = new StringBuilder("^");
        boolean inGroup = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '\\':
                    if (++i >= glob.length()) {
                        throw new PatternSyntaxException("No character to escape", glob, i - 1);
                    }
                    literal(sb, glob.charAt(i));
                    break;
                case '*':
                    boolean deep = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    sb.append(deep ? ".*" : notSep + "*");
                    i += deep ? 1 : 0;
                    break;
                case '?':
                    sb.append(notSep);
                    break;
                case '[':
                    i = bracket(glob, i, sb, sep, notSep);
                    break;
                case '{':
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest groups", glob, i);
                    }
                    // Observed: each alternative is a group of its own inside the group.
                    sb.append("(?:(?:");
                    inGroup = true;
                    break;
                case '}':
                    sb.append(inGroup ? "))" : "}");
                    inGroup = false;
                    break;
                case ',':
                    sb.append(inGroup ? ")|(?:" : ",");
                    break;
                case '/':
                    // Observed on Windows: a '/' in a glob matches the separator.
                    literal(sb, sep);
                    break;
                default:
                    literal(sb, c);
            }
        }
        if (inGroup) {
            // Observed: the message lacks the closing quote.
            throw new PatternSyntaxException("Missing '}", glob, glob.length() - 1);
        }
        return sb.append('$').toString();
    }

    private static void literal(StringBuilder sb, char c) {
        if (META.indexOf(c) >= 0) {
            sb.append('\\');
        }
        sb.append(c);
    }

    /**
     * The class at `start` (its `[`); returns the index of its `]`. Observed on the oracle:
     * a `-` first (after `[` or `[!`) is a member; elsewhere it makes a range from the char
     * before it, unless a `]` follows (then it is a member); a `-` with no char before it to
     * start a range (after a range, or after that first `-`), and a range that runs backwards
     * (reported at the char before the `-`), are "Invalid range"; the end of a range is passed
     * to the regular expression as it is, even a separator (so `[S-[]` is its "Unclosed
     * character class"); a separator elsewhere is refused.
     */
    private static int bracket(String glob, int start, StringBuilder sb, char sep, String notSep) {
        int i = start + 1;
        sb.append('[').append(notSep).append("&&[");
        boolean negated = i < glob.length() && glob.charAt(i) == '!';
        if (negated) {
            sb.append('^');
            i++;
        }
        if (i < glob.length() && glob.charAt(i) == '-') {
            sb.append('-');
            i++;
        }
        int last = -1;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == ']') {
                sb.append("]]");
                return i;
            }
            separator(glob, i, c, sep);
            if (c != '-') {
                // Observed: a '^' first in a plain class is no range start ("[^-a]" is refused).
                boolean caret = c == '^' && !negated && i == start + 1;
                member(sb, c, i > start + 1 ? glob.charAt(i - 1) : 0, caret);
                last = caret ? -1 : c;
                i++;
                continue;
            }
            if (last < 0) {
                throw new PatternSyntaxException("Invalid range", glob, i);
            }
            if (i + 1 < glob.length() && glob.charAt(i + 1) == ']') {
                sb.append('-');
                i++;
                continue;
            }
            if (i + 1 >= glob.length()) {
                throw new PatternSyntaxException("Missing ']", glob, i + 1);
            }
            char end = glob.charAt(i + 1);
            if (end < last) {
                throw new PatternSyntaxException("Invalid range", glob, i - 1);
            }
            sb.append('-').append(end);
            last = -1;
            i += 2;
        }
        throw new PatternSyntaxException("Missing ']", glob, glob.length() - 1);
    }

    private static void separator(String glob, int i, char c, char sep) {
        if (c == '/' || c == sep) {
            throw new PatternSyntaxException("Explicit 'name separator' in class", glob, i);
        }
    }

    /**
     * A member as the regular expression reads it. Classes are read by UTF-16 unit, as the
     * oracle's indexes show; a pair, passed on as it is, is one code point to the regex. Observed (indexes of the oracle's errors): only
     * a '[' or '\', a '^' first, and a '&' after another '&' are escaped.
     */
    private static void member(StringBuilder sb, char c, char before, boolean first) {
        if (c == '[' || c == '\\' || (c == '^' && first) || (c == '&' && before == '&')) {
            sb.append('\\');
        }
        sb.append(c);
    }
}
