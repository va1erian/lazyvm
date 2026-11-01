package java.io;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lazy.internal.FileSystem;
import lazy.internal.PathSyntax;

/**
 * A file name in the platform's syntax (lazy.internal.PathSyntax: Windows
 * or Unix, chosen at run time), and the file operations on it, which go
 * through lazy.internal.FileSystem (io.md section 7).
 */
public class File implements Serializable, Comparable<File> {
    private static final PathSyntax SYNTAX = PathSyntax.current();

    public static final char separatorChar = SYNTAX.separator();
    public static final String separator = String.valueOf(separatorChar);
    public static final char pathSeparatorChar = lazy.internal.VM.getProperty("path.separator").charAt(0);
    public static final String pathSeparator = String.valueOf(pathSeparatorChar);

    /** The temporary directory is fixed at its first use (observed), so in a class of its own. */
    private static final class TempDir {
        static final String PATH = System.getProperty("java.io.tmpdir");
    }

    private final String path;
    private final transient int prefix;

    public File(String pathname) {
        this(normalized(pathname), true);
    }

    // Spec F1: a null parent is the child alone; an empty one is the root.
    public File(String parent, String child) {
        this(join(parent, child), true);
    }

    public File(File parent, String child) {
        this(join(parent == null ? null : parent.path, child), true);
    }

    public File(URI uri) {
        this(normalized(fromUri(uri)), true);
    }

    /** A path already in normal form; observed: File(parent, child) keeps what the join gives. */
    private File(String normalizedPath, boolean normal) {
        this.path = normalizedPath;
        this.prefix = SYNTAX.prefixLength(path);
    }

    private static String normalized(String pathname) {
        if (pathname == null) {
            throw new NullPointerException();
        }
        return SYNTAX.normalizeFile(pathname);
    }

    private static String join(String parent, String child) {
        if (child == null) {
            throw new NullPointerException();
        }
        if (parent == null) {
            return SYNTAX.normalizeFile(child);
        }
        String p = parent.isEmpty() ? separator : SYNTAX.normalizeFile(parent);
        return SYNTAX.resolveFile(p, SYNTAX.normalizeFile(child));
    }

    /** Javadoc of File(URI): an absolute, hierarchical "file:" URI with a path only. */
    private static String fromUri(URI uri) {
        if (!uri.isAbsolute()) {
            throw new IllegalArgumentException("URI is not absolute");
        }
        if (uri.isOpaque()) {
            throw new IllegalArgumentException("URI is not hierarchical");
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("URI scheme is not \"file\"");
        }
        if (uri.getRawAuthority() != null) {
            throw new IllegalArgumentException("URI has an authority component");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("URI has a fragment component");
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("URI has a query component");
        }
        String p = uri.getPath();
        if (p.isEmpty()) {
            throw new IllegalArgumentException("URI path component is empty");
        }
        // "/C:/x" names C:\x on Windows.
        if (separatorChar == '\\' && p.length() > 2 && p.charAt(0) == '/' && p.charAt(2) == ':') {
            p = p.substring(1);
        }
        return p;
    }

    public String getName() {
        int i = path.lastIndexOf(separatorChar);
        return i < prefix ? path.substring(prefix) : path.substring(i + 1);
    }

    public String getParent() {
        int i = path.lastIndexOf(separatorChar);
        if (i < prefix) {
            return prefix > 0 && path.length() > prefix ? path.substring(0, prefix) : null;
        }
        return path.substring(0, i);
    }

    // Observed: the parent string is kept as it is (`\C:\` has the parent file `\C:`).
    public File getParentFile() {
        String p = getParent();
        return p == null ? null : new File(p, true);
    }

    public String getPath() {
        return path;
    }

    public boolean isAbsolute() {
        return SYNTAX.isAbsoluteFile(path);
    }

    public String getAbsolutePath() {
        return SYNTAX.absoluteFile(path, System.getProperty("user.dir"));
    }

    public File getAbsoluteFile() {
        return new File(getAbsolutePath());
    }

    public String getCanonicalPath() throws IOException {
        if (invalid(path)) {
            throw new IOException("Invalid file path");
        }
        return SYNTAX.canonical(getAbsolutePath());
    }

    /** Observed: a path holding a NUL char names no file ("Invalid file path"). */
    static boolean invalid(String path) {
        return path.indexOf('\u0000') >= 0;
    }

    /** `name` for a file stream to open, or the FileNotFoundException Java 8 gives for it. */
    static String openable(String name) throws FileNotFoundException {
        if (invalid(name)) {
            throw new FileNotFoundException("Invalid file path");
        }
        return name;
    }

    // Observed: kept as given, so a trailing separator of the canonical form stays.
    public File getCanonicalFile() throws IOException {
        return new File(getCanonicalPath(), true);
    }

    // Spec F6: a directory's URI ends with '/'; what a URI path cannot hold is escaped.
    public URI toURI() {
        String p = getAbsolutePath().replace(separatorChar, '/');
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.startsWith("//")) {
            // Observed: a UNC path keeps an empty authority before it, file:////host/share.
            p = "//" + p;
        }
        // Observed: judged on the absolute path, so "" (the current directory) ends with '/'.
        if (!p.endsWith("/") && new File(getAbsolutePath()).isDirectory()) {
            p += "/";
        }
        try {
            return new URI("file", null, p, null);
        } catch (URISyntaxException e) {
            throw new InternalError(e);
        }
    }

    public Path toPath() {
        return Paths.get(path);
    }

    private int attributes() {
        return FileSystem.attributes(path);
    }

    private boolean has(int bit) {
        return (attributes() & bit) != 0;
    }

    public boolean exists() {
        return has(FileSystem.EXISTS);
    }

    public boolean isFile() {
        return has(FileSystem.REGULAR);
    }

    public boolean isDirectory() {
        return has(FileSystem.DIRECTORY);
    }

    public boolean isHidden() {
        return has(FileSystem.HIDDEN);
    }

    public boolean canRead() {
        return has(FileSystem.READABLE);
    }

    public boolean canWrite() {
        return has(FileSystem.WRITABLE);
    }

    public boolean canExecute() {
        return has(FileSystem.EXECUTABLE);
    }

    public long lastModified() {
        long us = FileSystem.fileTime(path, FileSystem.MODIFIED);
        return us == Long.MIN_VALUE ? 0 : Math.floorDiv(us, 1000L);
    }

    public long length() {
        return FileSystem.length(path);
    }

    public boolean createNewFile() throws IOException {
        if (invalid(path)) {
            throw new IOException("Invalid file path");
        }
        return FileSystem.createNew(path);
    }

    public boolean delete() {
        try {
            FileSystem.delete(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public String[] list() {
        return FileSystem.list(path);
    }

    public String[] list(FilenameFilter filter) {
        String[] names = list();
        if (names == null || filter == null) {
            return names;
        }
        List<String> kept = new ArrayList<>();
        for (String n : names) {
            if (filter.accept(this, n)) {
                kept.add(n);
            }
        }
        return kept.toArray(new String[0]);
    }

    // Spec F5: entries are new File(this, name).
    public File[] listFiles() {
        return listFiles((FileFilter) null);
    }

    public File[] listFiles(FilenameFilter filter) {
        String[] names = list(filter);
        if (names == null) {
            return null;
        }
        File[] files = new File[names.length];
        for (int i = 0; i < names.length; i++) {
            files[i] = new File(this, names[i]);
        }
        return files;
    }

    public File[] listFiles(FileFilter filter) {
        String[] names = list();
        if (names == null) {
            return null;
        }
        List<File> kept = new ArrayList<>();
        for (String n : names) {
            File f = new File(this, n);
            if (filter == null || filter.accept(f)) {
                kept.add(f);
            }
        }
        return kept.toArray(new File[0]);
    }

    public boolean mkdir() {
        return FileSystem.mkdir(path);
    }

    // Spec F4: makes the missing parents; false if the directory is already there.
    public boolean mkdirs() {
        if (exists()) {
            return false;
        }
        if (mkdir()) {
            return true;
        }
        File canonical;
        try {
            canonical = getCanonicalFile();
        } catch (IOException e) {
            return false;
        }
        File parent = canonical.getParentFile();
        return parent != null && (parent.mkdirs() || parent.exists()) && canonical.mkdir();
    }

    // Observed on Windows: renaming onto an existing file fails there (Unix replaces it).
    public boolean renameTo(File dest) {
        if (dest == null) {
            throw new NullPointerException();
        }
        // A case-only rename, or one to itself, names the same file and succeeds.
        if (separatorChar == '\\' && dest.exists() && !sameFile(dest)) {
            return false;
        }
        return FileSystem.rename(path, dest.path);
    }

    private boolean sameFile(File other) {
        try {
            return getCanonicalPath().equalsIgnoreCase(other.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    // Spec F5: a negative time is refused.
    public boolean setLastModified(long time) {
        if (time < 0) {
            throw new IllegalArgumentException("Negative time");
        }
        // A time the platform cannot hold is false (Java 8 reports true for some it then garbles).
        if (time > Long.MAX_VALUE / 1000) {
            return false;
        }
        try {
            FileSystem.setFileTime(path, FileSystem.MODIFIED, time * 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean setReadOnly() {
        return FileSystem.setReadOnly(path);
    }

    public boolean setWritable(boolean writable, boolean ownerOnly) {
        return access(2, writable, ownerOnly);
    }

    public boolean setWritable(boolean writable) {
        return setWritable(writable, true);
    }

    public boolean setReadable(boolean readable, boolean ownerOnly) {
        return access(4, readable, ownerOnly);
    }

    // A path holding a NUL char names no file, so no access can change (observed: false).
    private boolean access(int bits, boolean enable, boolean ownerOnly) {
        return !invalid(path) && FileSystem.setAccess(path, bits, enable, ownerOnly);
    }

    public boolean setReadable(boolean readable) {
        return setReadable(readable, true);
    }

    public boolean setExecutable(boolean executable, boolean ownerOnly) {
        return access(1, executable, ownerOnly);
    }

    public boolean setExecutable(boolean executable) {
        return setExecutable(executable, true);
    }

    public static File[] listRoots() {
        if (separatorChar != '\\') {
            return new File[] {new File("/")};
        }
        List<File> roots = new ArrayList<>();
        for (char d = 'A'; d <= 'Z'; d++) {
            File root = new File(d + ":\\");
            if (root.exists()) {
                roots.add(root);
            }
        }
        return roots.toArray(new File[0]);
    }

    // Spec F6: prefix + digits + suffix (".tmp" if null), in the canonical temp directory.
    public static File createTempFile(String prefix, String suffix, File directory) throws IOException {
        if (prefix.length() < 3) {
            throw new IllegalArgumentException("Prefix string too short");
        }
        String s = suffix == null ? ".tmp" : suffix;
        File dir = directory != null ? directory
                : new File(TempDir.PATH).getCanonicalFile();
        // Observed: the prefix is cut to its last name; a suffix that makes more than one name is
        // "Unable to create temporary file", so the file never lands outside the directory.
        String pre = new File(prefix).getName();
        while (true) {
            String name = pre + lazy.internal.TempNames.next() + s;
            File f = new File(dir, name);
            if (!name.equals(f.getName()) || invalid(f.getPath())) {
                throw new IOException("Unable to create temporary file, " + f.getPath());
            }
            if (f.createNewFile()) {
                return f;
            }
        }
    }

    public static File createTempFile(String prefix, String suffix) throws IOException {
        return createTempFile(prefix, suffix, null);
    }

    // Spec F2: path strings, ignoring case on Windows.
    public int compareTo(File other) {
        return SYNTAX.ignoresCase() ? path.compareToIgnoreCase(other.path) : path.compareTo(other.path);
    }

    public boolean equals(Object obj) {
        return obj instanceof File && compareTo((File) obj) == 0;
    }

    // Spec F2: the path's hash ^ 1234321, of the lower-cased path on Windows.
    public int hashCode() {
        String p = SYNTAX.ignoresCase() ? path.toLowerCase(Locale.ROOT) : path;
        return p.hashCode() ^ 1234321;
    }

    public String toString() {
        return path;
    }
}
