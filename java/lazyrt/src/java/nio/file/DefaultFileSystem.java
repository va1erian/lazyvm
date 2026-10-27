package java.nio.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import lazy.internal.PathSyntax;
import lazy.internal.Regex;

/** The platform's file system: paths in lazy.internal.PathSyntax, files through the natives. */
final class DefaultFileSystem extends FileSystem {
    static final PathSyntax SYNTAX = PathSyntax.current();

    public void close() {
        throw new UnsupportedOperationException();
    }

    public boolean isOpen() {
        return true;
    }

    public boolean isReadOnly() {
        return false;
    }

    public String getSeparator() {
        return String.valueOf(SYNTAX.separator());
    }

    public Iterable<Path> getRootDirectories() {
        List<Path> roots = new ArrayList<>();
        for (File f : File.listRoots()) {
            roots.add(f.toPath());
        }
        return roots;
    }

    // Spec P1: empty parts are skipped.
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String part : more) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SYNTAX.separator());
            }
            sb.append(part);
        }
        return DefaultPath.parse(this, sb.toString());
    }

    // Spec P3: "glob:" or "regex:", in lower case; a glob must match the whole path.
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        String regex;
        if (syntax.equals("glob")) {
            regex = globToRegex(pattern, SYNTAX.separator());
        } else if (syntax.equals("regex")) {
            regex = pattern;
        } else {
            throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
        }
        final Regex re = Regex.compile(regex, SYNTAX.ignoresCase());
        return new PathMatcher() {
            public boolean matches(Path path) {
                return re.matches(path.toString());
            }
        };
    }

    private static final String REGEX_META = "\\^$.|?*+()[]{}";

    /**
     * Javadoc of getPathMatcher: `*` and `?` stay within a name, `**` crosses
     * names, `[...]` and `[!...]` classes, `{a,b}` groups (not nested), `\` escapes.
     */
    static String globToRegex(String glob, char sep) {
        String notSep = sep == '\\' ? "[^\\\\]" : "[^/]";
        StringBuilder sb = new StringBuilder();
        boolean inGroup = false;
        boolean emptyClass = false;
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
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++;
                    } else {
                        sb.append(notSep).append('*');
                    }
                    break;
                case '?':
                    sb.append(notSep);
                    break;
                case '[':
                    int end = bracket(glob, i, sb, sep);
                    emptyClass |= end == i + 1 || (end == i + 2 && glob.charAt(i + 1) == '!');
                    i = end;
                    break;
                case '{':
                    if (inGroup) {
                        throw new PatternSyntaxException("Cannot nest groups", glob, i);
                    }
                    sb.append("(?:");
                    inGroup = true;
                    break;
                case '}':
                    if (inGroup) {
                        sb.append(')');
                        inGroup = false;
                    } else {
                        literal(sb, c);
                    }
                    break;
                case ',':
                    if (inGroup) {
                        sb.append('|');
                    } else {
                        literal(sb, c);
                    }
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
        if (emptyClass) {
            // Observed: an empty class passes the glob checks but not the regular expression.
            throw new PatternSyntaxException("Unclosed character class", sb.toString(), sb.length() - 1);
        }
        return sb.toString();
    }

    private static void literal(StringBuilder sb, char c) {
        if (REGEX_META.indexOf(c) >= 0) {
            sb.append('\\');
        }
        sb.append(c);
    }

    /**
     * A `[...]` class from `start`; returns the index of its `]`. Observed: a
     * `]` right after `[` or `[!` ends an empty class; a separator inside is
     * refused; `[!...]` does not match the separator either.
     */
    private static int bracket(String glob, int start, StringBuilder sb, char sep) {
        int i = start + 1;
        sb.append('[');
        if (i < glob.length() && glob.charAt(i) == '!') {
            sb.append('^');
            literal(sb, sep);
            i++;
        }
        for (; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == ']') {
                sb.append(']');
                return i;
            }
            if (c == '/' || c == sep) {
                throw new PatternSyntaxException("Explicit 'name separator' in class", glob, i);
            }
            if (c != '-' && !Character.isLetterOrDigit(c)) {
                sb.append('\\');
            }
            sb.append(c);
        }
        throw new PatternSyntaxException("Missing ']", glob, glob.length() - 1);
    }
}
