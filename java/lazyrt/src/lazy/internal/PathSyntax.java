package lazy.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The platform's path rules, shared by java.io.File and java.nio.file: one
 * implementation per platform, chosen at run time from `file.separator`
 * (io.md F1, W1, section 14; nio-file.md P1, PW1). File keeps a normalized string and its
 * prefix; a nio path is a root and names.
 */
public abstract class PathSyntax {
    private static final PathSyntax CURRENT =
            "\\".equals(System.getProperty("file.separator")) ? new WindowsPaths() : new UnixPaths();

    public static PathSyntax current() {
        return CURRENT;
    }

    public abstract char separator();

    /** Spec F2, PW1: whether names compare ignoring case. */
    public abstract boolean ignoresCase();

    // ---- java.io.File ----

    /** The path File keeps: separators normalized, repeated and trailing ones gone. */
    public abstract String normalizeFile(String path);

    /** The length of the root part of a normalized File path (0 for a relative one). */
    public abstract int prefixLength(String path);

    public abstract boolean isAbsoluteFile(String path);

    /** File(parent, child) of normalized parts. */
    public String resolveFile(String parent, String child) {
        char sep = separator();
        int start = 0;
        while (start < child.length() && child.charAt(start) == sep) {
            start++;
        }
        String rest = child.substring(start);
        if (child.isEmpty()) {
            return parent;
        }
        // Observed: a child of separators only still adds one separator.
        return parent.endsWith(String.valueOf(sep)) ? parent + rest : parent + sep + rest;
    }

    /** The absolute form of a normalized File path, `userDir` being the current directory. */
    public abstract String absoluteFile(String path, String userDir);

    /** Before the native canonicalization: checks and lexical clean-up the platform needs. */
    public abstract String precanonical(String absolute) throws java.io.IOException;

    /** After the native canonicalization: the platform's final touches. */
    String postcanonical(String absolute, String canonical) {
        return canonical;
    }

    /** The canonical form of an absolute path: checks, the native, then the platform's touches. */
    public String canonical(String absolute) throws java.io.IOException {
        String checked = precanonical(absolute);
        return postcanonical(checked, FileSystem.canonicalize(checked));
    }

    // ---- java.nio.file ----

    /** A parsed nio path: its root (null if none) and its names. */
    public static final class Parsed {
        public final String root;
        public final String[] names;

        public Parsed(String root, String[] names) {
            this.root = root;
            this.names = names;
        }
    }

    /** Spec P1, PW1: parses `input`, or throws InvalidPathException. */
    public abstract Parsed parsePath(String input);

    /** 0 no root, 1 drive-relative (`C:`), 2 root-relative (`\`), 3 absolute, 4 absolute UNC. */
    public abstract int rootKind(String root);

    /**
     * The root `other`'s root becomes when resolved against `base`'s root,
     * for a root that is not absolute (Windows only); null when `other` wins.
     */
    public String resolveRoot(String base, String other) {
        return null;
    }

    /** The absolute form of a parsed nio path that is not absolute. */
    public abstract String absolutePath(Parsed p, String userDir);

    /** A "file:" URI string for an absolute path string. */
    public abstract String uriPath(String absolute);

    // ---- shared helpers ----

    /** Splits `s` from `from` at `sep`, dropping empty pieces. */
    static List<String> split(String s, int from, char sep) {
        List<String> names = new ArrayList<>();
        int i = from;
        while (i < s.length()) {
            int j = s.indexOf(sep, i);
            if (j < 0) {
                j = s.length();
            }
            if (j > i) {
                names.add(s.substring(i, j));
            }
            i = j + 1;
        }
        return names;
    }

    /** Collapses repeated `sep` from `from` on and drops a trailing one beyond `from`. */
    static String collapse(String s, int from, char sep) {
        StringBuilder sb = new StringBuilder(s.length());
        sb.append(s, 0, from);
        boolean last = from > 0 && s.charAt(from - 1) == sep;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == sep && last) {
                continue;
            }
            sb.append(c);
            last = c == sep;
        }
        if (sb.length() > from && sb.charAt(sb.length() - 1) == sep) {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** Resolves "." and ".." names of an absolute path after its `prefix` root, lexically. */
    static String collapseDots(String abs, int prefix, char sep) {
        List<String> out = new ArrayList<>();
        for (String n : split(abs, prefix, sep)) {
            if (n.equals("..")) {
                if (!out.isEmpty()) {
                    out.remove(out.size() - 1);
                }
            } else if (!n.equals(".")) {
                out.add(n);
            }
        }
        StringBuilder sb = new StringBuilder(abs.substring(0, prefix));
        for (int i = 0; i < out.size(); i++) {
            if (i > 0 || (sb.length() > 0 && sb.charAt(sb.length() - 1) != sep)) {
                sb.append(sep);
            }
            sb.append(out.get(i));
        }
        return sb.toString();
    }

    /** A path as a URI path: what it cannot hold escaped (UriText, the one escaping helper). */
    static String quotePath(String path) {
        return UriText.escape(path, UriText.PATH, true);
    }
}
