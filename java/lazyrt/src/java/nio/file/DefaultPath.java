package java.nio.file;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import lazy.internal.PathSyntax;

/** A path of the default file system: a root (or none) and names (nio-file.md P1, P2, PW1). */
final class DefaultPath implements Path {
    private static final PathSyntax SYNTAX = DefaultFileSystem.SYNTAX;

    private final DefaultFileSystem fs;
    private final String string;
    private final String root;
    private final String[] names;

    private DefaultPath(DefaultFileSystem fs, String string, String root, String[] names) {
        this.fs = fs;
        this.string = string;
        this.root = root;
        this.names = names;
    }

    static DefaultPath parse(DefaultFileSystem fs, String input) {
        PathSyntax.Parsed p = SYNTAX.parsePath(input);
        return of(fs, p.root, p.names);
    }

    private static DefaultPath of(DefaultFileSystem fs, String root, String[] names) {
        StringBuilder sb = new StringBuilder(root == null ? "" : root);
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                sb.append(SYNTAX.separator());
            }
            sb.append(names[i]);
        }
        return new DefaultPath(fs, sb.toString(), root, names);
    }

    private DefaultPath with(String newRoot, String[] newNames) {
        if (newRoot == null && newNames.length == 0) {
            newNames = new String[] {""};
        }
        return of(fs, newRoot, newNames);
    }

    private static DefaultPath check(Path p) {
        if (!(p instanceof DefaultPath)) {
            throw p == null ? new NullPointerException() : new ProviderMismatchException();
        }
        return (DefaultPath) p;
    }

    /** The empty path "": no root, one empty name (Spec P1). */
    private boolean isEmpty() {
        return root == null && names.length == 1 && names[0].isEmpty();
    }

    public FileSystem getFileSystem() {
        return fs;
    }

    public boolean isAbsolute() {
        return SYNTAX.rootKind(root) >= 3;
    }

    public Path getRoot() {
        return root == null ? null : of(fs, root, new String[0]);
    }

    public Path getFileName() {
        return names.length == 0 ? null : of(fs, null, new String[] {names[names.length - 1]});
    }

    public Path getParent() {
        if (names.length == 0 || (names.length == 1 && root == null)) {
            return null;
        }
        return of(fs, root, Arrays.copyOf(names, names.length - 1));
    }

    public int getNameCount() {
        return names.length;
    }

    public Path getName(int index) {
        return subpath(index, index + 1);
    }

    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || beginIndex >= names.length || endIndex > names.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException();
        }
        return of(fs, null, Arrays.copyOfRange(names, beginIndex, endIndex));
    }

    private boolean same(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return SYNTAX.ignoresCase() ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    // Spec P2: whole names, the same root.
    public boolean startsWith(Path other) {
        DefaultPath o = check(other);
        if (!same(root, o.root) || o.names.length > names.length) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!same(names[i], o.names[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean startsWith(String other) {
        return startsWith(fs.getPath(other));
    }

    public boolean endsWith(Path other) {
        DefaultPath o = check(other);
        if (o.root != null) {
            return equals(o);
        }
        int k = names.length - o.names.length;
        if (k < 0) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!same(names[k + i], o.names[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean endsWith(String other) {
        return endsWith(fs.getPath(other));
    }

    // Spec P1: drops "." and "name/.."; a leading ".." stays unless a root is above it.
    public Path normalize() {
        String[] out = new String[names.length];
        int n = 0;
        boolean rooted = SYNTAX.rootKind(root) >= 2;
        for (String name : names) {
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (n > 0 && !out[n - 1].equals("..")) {
                    n--;
                    continue;
                }
                if (rooted) {
                    continue;
                }
            }
            out[n++] = name;
        }
        return with(root, Arrays.copyOf(out, n));
    }

    // Spec P2, PW1: an absolute argument wins; "" changes nothing; Windows roots combine.
    public Path resolve(Path other) {
        DefaultPath o = check(other);
        if (o.isAbsolute()) {
            return o;
        }
        if (o.isEmpty()) {
            return this;
        }
        if (o.root != null) {
            String r = SYNTAX.resolveRoot(root, o.root);
            if (r == null) {
                return o;
            }
            if (SYNTAX.rootKind(o.root) == 2) {
                return with(r, o.names);
            }
            // Observed: a drive-relative argument is appended as written, so `C:` adds a bare `\`.
            String tail = of(fs, null, o.names).string;
            String sep = string.endsWith(String.valueOf(SYNTAX.separator())) ? "" : String.valueOf(SYNTAX.separator());
            String[] joined = Arrays.copyOf(names, names.length + o.names.length);
            System.arraycopy(o.names, 0, joined, names.length, o.names.length);
            return new DefaultPath(fs, string + sep + tail, root, joined);
        }
        String[] base = isEmpty() ? new String[0] : names;
        String[] joined = Arrays.copyOf(base, base.length + o.names.length);
        System.arraycopy(o.names, 0, joined, base.length, o.names.length);
        return with(root, joined);
    }

    public Path resolve(String other) {
        return resolve(fs.getPath(other));
    }

    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? check(other) : parent.resolve(other);
    }

    public Path resolveSibling(String other) {
        return resolveSibling(fs.getPath(other));
    }

    // Spec P2, PW1: no normalization; ".." per name left over here, then the other's rest.
    public Path relativize(Path other) {
        DefaultPath o = check(other);
        if (SYNTAX.rootKind(root) != SYNTAX.rootKind(o.root)) {
            throw new IllegalArgumentException("'other' is different type of Path");
        }
        if (!same(root, o.root)) {
            throw new IllegalArgumentException("'other' has different root");
        }
        if (equals(o)) {
            return with(null, new String[0]);
        }
        int common = 0;
        while (common < names.length && common < o.names.length && same(names[common], o.names[common])) {
            common++;
        }
        int ups = names.length - common;
        String[] out = new String[ups + o.names.length - common];
        Arrays.fill(out, 0, ups, "..");
        System.arraycopy(o.names, common, out, ups, o.names.length - common);
        return of(fs, null, out);
    }

    public URI toUri() {
        String abs = toAbsolutePath().toString();
        String u = SYNTAX.uriPath(abs);
        boolean dir = (lazy.internal.FileSystem.attributes(abs) & lazy.internal.FileSystem.DIRECTORY) != 0;
        if (!u.endsWith("/") && (names.length == 0 || dir)) {
            u += "/";
        }
        return URI.create(u);
    }

    public Path toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        String userDir = System.getProperty("user.dir");
        String abs = SYNTAX.absolutePath(new PathSyntax.Parsed(root, names), userDir);
        // Observed: the string is kept as built, even with a trailing separator (Windows `C:`).
        PathSyntax.Parsed p = SYNTAX.parsePath(abs);
        return new DefaultPath(fs, abs, p.root, p.names);
    }

    // Observed: the OS resolves the path as written (on Unix a ".." after a link leaves its
    // target); one it cannot resolve is reported absolute, "." and ".." applied by name and,
    // on Windows, its names trimmed (lazy.internal.FileSystem.lexical), with the OS's reason
    // when it is not simply missing ("The directory name is invalid." below a file).
    public Path toRealPath(LinkOption... options) throws IOException {
        String abs = toAbsolutePath().toString();
        try {
            return parse(fs, SYNTAX.real(abs));
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException(lazy.internal.FileSystem.lexical(abs));
        } catch (IOException e) {
            throw new FileSystemException(lazy.internal.FileSystem.lexical(abs), null, e.getMessage());
        }
    }

    public File toFile() {
        return new File(string);
    }

    public Iterator<Path> iterator() {
        return new Iterator<Path>() {
            private int i;

            public boolean hasNext() {
                return i < names.length;
            }

            public Path next() {
                if (i >= names.length) {
                    throw new NoSuchElementException();
                }
                return getName(i++);
            }
        };
    }

    // Spec P1, PW1: the path strings, ignoring case on Windows.
    public int compareTo(Path other) {
        String o = check(other).string;
        if (!SYNTAX.ignoresCase()) {
            return string.compareTo(o);
        }
        int n = Math.min(string.length(), o.length());
        for (int i = 0; i < n; i++) {
            int d = Character.toUpperCase(string.charAt(i)) - Character.toUpperCase(o.charAt(i));
            if (d != 0) {
                return d;
            }
        }
        return string.length() - o.length();
    }

    public boolean equals(Object other) {
        return other instanceof DefaultPath && compareTo((DefaultPath) other) == 0;
    }

    public int hashCode() {
        if (!SYNTAX.ignoresCase()) {
            return string.hashCode();
        }
        int h = 0;
        for (int i = 0; i < string.length(); i++) {
            h = 31 * h + Character.toUpperCase(string.charAt(i));
        }
        return h;
    }

    public String toString() {
        return string;
    }
}
