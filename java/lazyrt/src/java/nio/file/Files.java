package java.nio.file;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lazy.internal.FileSystem;

/**
 * Operations on files (nio-file.md section 2). The work is in FileAccess (opening),
 * FileOps (changes), FileAttrs (attributes) and FileTree (listing, walking).
 * File attributes at creation, links and the stream methods (S11) are out of scope.
 */
public final class Files {
    private Files() {
    }

    public static InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return FileAccess.openInput(path, options);
    }

    public static OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return FileAccess.openOutput(path, options);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir) throws IOException {
        return FileTree.open(dir, null);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir, String glob) throws IOException {
        return FileTree.open(dir, FileTree.glob(glob));
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        return FileTree.open(dir, filter);
    }

    public static Path createFile(Path path, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createFile(path);
    }

    public static Path createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createDirectory(dir);
    }

    public static Path createDirectories(Path dir, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createDirectories(dir);
    }

    public static Path createTempFile(Path dir, String prefix, String suffix, FileAttribute<?>... attrs)
            throws IOException {
        return FileOps.createTemp(dir, prefix, suffix, false);
    }

    public static Path createTempFile(String prefix, String suffix, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createTemp(null, prefix, suffix, false);
    }

    public static Path createTempDirectory(Path dir, String prefix, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createTemp(dir, prefix, null, true);
    }

    public static Path createTempDirectory(String prefix, FileAttribute<?>... attrs) throws IOException {
        return FileOps.createTemp(null, prefix, null, true);
    }

    public static void delete(Path path) throws IOException {
        FileOps.delete(path);
    }

    public static boolean deleteIfExists(Path path) throws IOException {
        if (!FileAccess.exists(path)) {
            return false;
        }
        FileOps.delete(path);
        return true;
    }

    public static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        return FileOps.copy(source, target, options);
    }

    public static Path move(Path source, Path target, CopyOption... options) throws IOException {
        return FileOps.move(source, target, options);
    }

    public static long copy(InputStream in, Path target, CopyOption... options) throws IOException {
        return FileOps.copy(in, target, options);
    }

    public static long copy(Path source, OutputStream out) throws IOException {
        try (InputStream in = newInputStream(source)) {
            return FileOps.transfer(in, out);
        }
    }

    public static boolean isSameFile(Path path, Path path2) throws IOException {
        return FileOps.isSameFile(path, path2);
    }

    private static boolean has(Path path, int bit) {
        return (FileAccess.attributes(path) & bit) != 0;
    }

    // Observed on Windows: the hidden flag needs the file, so a missing one is NoSuchFileException.
    // On Unix (Javadoc) hidden means a name starting with '.', which needs no file.
    public static boolean isHidden(Path path) throws IOException {
        if ("\\".equals(path.getFileSystem().getSeparator())) {
            FileAccess.requireExists(path);
            return has(path, FileSystem.HIDDEN);
        }
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    public static boolean isSymbolicLink(Path path) {
        return has(path, FileSystem.LINK);
    }

    public static boolean isDirectory(Path path, LinkOption... options) {
        return has(path, FileSystem.DIRECTORY);
    }

    public static boolean isRegularFile(Path path, LinkOption... options) {
        return has(path, FileSystem.REGULAR);
    }

    public static boolean exists(Path path, LinkOption... options) {
        return has(path, FileSystem.EXISTS);
    }

    public static boolean notExists(Path path, LinkOption... options) {
        return !exists(path);
    }

    public static boolean isReadable(Path path) {
        return has(path, FileSystem.READABLE);
    }

    public static boolean isWritable(Path path) {
        return has(path, FileSystem.WRITABLE);
    }

    public static boolean isExecutable(Path path) {
        return has(path, FileSystem.EXECUTABLE);
    }

    public static FileTime getLastModifiedTime(Path path, LinkOption... options) throws IOException {
        return FileAttrs.read(path).lastModifiedTime();
    }

    public static Path setLastModifiedTime(Path path, FileTime time) throws IOException {
        FileAttrs.setTime(path, FileSystem.MODIFIED, time);
        return path;
    }

    public static long size(Path path) throws IOException {
        return FileAttrs.read(path).size();
    }

    @SuppressWarnings("unchecked")
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException();
        }
        return (A) FileAttrs.read(path);
    }

    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        return FileAttrs.readMap(path, attributes);
    }

    public static Object getAttribute(Path path, String attribute, LinkOption... options) throws IOException {
        return FileAttrs.getOne(path, attribute);
    }

    public static Path setAttribute(Path path, String attribute, Object value, LinkOption... options)
            throws IOException {
        FileAttrs.set(path, attribute, value);
        return path;
    }

    public static Path walkFileTree(Path start, Set<FileVisitOption> options, int maxDepth,
            FileVisitor<? super Path> visitor) throws IOException {
        if (options == null) {
            throw new NullPointerException();
        }
        return FileTree.walk(start, maxDepth, visitor);
    }

    public static Path walkFileTree(Path start, FileVisitor<? super Path> visitor) throws IOException {
        return FileTree.walk(start, Integer.MAX_VALUE, visitor);
    }

    // Spec NF3: readers and writers report malformed and unmappable input.
    public static BufferedReader newBufferedReader(Path path, Charset cs) throws IOException {
        return new BufferedReader(new InputStreamReader(newInputStream(path), cs.newDecoder()));
    }

    public static BufferedReader newBufferedReader(Path path) throws IOException {
        return newBufferedReader(path, StandardCharsets.UTF_8);
    }

    public static BufferedWriter newBufferedWriter(Path path, Charset cs, OpenOption... options) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(newOutputStream(path, options), cs.newEncoder()));
    }

    public static BufferedWriter newBufferedWriter(Path path, OpenOption... options) throws IOException {
        return newBufferedWriter(path, StandardCharsets.UTF_8, options);
    }

    public static byte[] readAllBytes(Path path) throws IOException {
        try (InputStream in = newInputStream(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.available() + 1);
            FileOps.transfer(in, out);
            return out.toByteArray();
        }
    }

    public static List<String> readAllLines(Path path, Charset cs) throws IOException {
        try (BufferedReader r = newBufferedReader(path, cs)) {
            return FileOps.readAllLines(r);
        }
    }

    public static List<String> readAllLines(Path path) throws IOException {
        return readAllLines(path, StandardCharsets.UTF_8);
    }

    public static Path write(Path path, byte[] bytes, OpenOption... options) throws IOException {
        try (OutputStream out = newOutputStream(path, options)) {
            out.write(bytes);
        }
        return path;
    }

    // Spec NF2: each line and line.separator, encoded with REPORT.
    public static Path write(Path path, Iterable<? extends CharSequence> lines, Charset cs, OpenOption... options)
            throws IOException {
        try (BufferedWriter w = newBufferedWriter(path, cs, options)) {
            for (CharSequence line : lines) {
                w.append(line);
                w.newLine();
            }
        }
        return path;
    }

    public static Path write(Path path, Iterable<? extends CharSequence> lines, OpenOption... options)
            throws IOException {
        return write(path, lines, StandardCharsets.UTF_8, options);
    }
}
