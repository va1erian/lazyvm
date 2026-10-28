package java.nio.file;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lazy.internal.PathSyntax;

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
            regex = Globs.toRegex(pattern, SYNTAX.separator());
        } else if (syntax.equals("regex")) {
            regex = pattern;
        } else {
            throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
        }
        // Spec PW1: on Windows matching ignores case.
        int flags = SYNTAX.ignoresCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        final Pattern re = Pattern.compile(regex, flags);
        return new PathMatcher() {
            public boolean matches(Path path) {
                return re.matcher(path.toString()).matches();
            }
        };
    }
}
