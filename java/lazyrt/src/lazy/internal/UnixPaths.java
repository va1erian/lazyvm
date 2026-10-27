package lazy.internal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.List;

/** Unix path rules (LazyOS): one root `/`, `\` an ordinary char, case-sensitive names (io.md 14). */
final class UnixPaths extends PathSyntax {
    public char separator() {
        return '/';
    }

    public boolean ignoresCase() {
        return false;
    }

    // Spec F1: repeated and trailing separators go, a lone `/` stays.
    public String normalizeFile(String path) {
        return collapse(path, path.startsWith("/") ? 1 : 0, '/');
    }

    public int prefixLength(String path) {
        return path.startsWith("/") ? 1 : 0;
    }

    public boolean isAbsoluteFile(String path) {
        return path.startsWith("/");
    }

    public String absoluteFile(String path, String userDir) {
        if (isAbsoluteFile(path)) {
            return path;
        }
        return path.isEmpty() ? userDir : resolveFile(userDir, path);
    }

    public Parsed parsePath(String input) {
        if (input.indexOf('\u0000') >= 0) {
            throw new InvalidPathException(input, "Nul character not allowed");
        }
        String root = input.startsWith("/") ? "/" : null;
        List<String> names = split(input, 0, '/');
        if (root == null && names.isEmpty()) {
            names.add("");
        }
        return new Parsed(root, names.toArray(new String[0]));
    }

    public int rootKind(String root) {
        return root == null ? 0 : 3;
    }

    public String absolutePath(Parsed p, String userDir) {
        StringBuilder sb = new StringBuilder(userDir);
        for (String name : p.names) {
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            sb.append(name);
        }
        return sb.toString();
    }

    public String uriPath(String absolute) {
        return "file://" + quotePath(absolute);
    }
}
