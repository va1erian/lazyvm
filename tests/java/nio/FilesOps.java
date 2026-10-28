package nio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.Callable;

/** java.nio.file.Files in a fresh temporary directory: read, write, copy, move, delete, the
 *  directory makers, buffered readers/writers, basic attributes, and the exceptions with their
 *  messages (paths shown relative to the directory, the separator as '/'). */
public class FilesOps {
    static Path root;

    static String rel(Object o) {
        if (o == null) return "null";
        String s = o.toString(), r = root.toString();
        s = s.replace(r, "<tmp>");
        return s.replace(File.separatorChar, '/');
    }

    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = rel(c.call());
        } catch (Throwable e) {
            String m = e instanceof FileSystemException && ((FileSystemException) e).getReason() != null ? rel(((FileSystemException) e).getFile()) + " (reason hidden)" : rel(e.getMessage());
            r = "!" + e.getClass().getName() + ": " + m;
        }
        System.out.println(label + ": " + r);
    }

    static String text(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace(System.lineSeparator(), "$").replace("\r", "\\r").replace("\n", "\\n");
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazynio");
        t("createTempDirectory shape", () -> root.getFileName().toString().matches("lazynio\\d+") + " " + Files.isDirectory(root));
        t("createTempFile shape", () -> { Path f = Files.createTempFile(root, "pre", null); boolean ok = f.getFileName().toString().matches("pre\\d+\\.tmp"); Files.delete(f); return ok; });
        t("createTempFile suffix", () -> { Path f = Files.createTempFile(root, null, ".x"); boolean ok = f.getFileName().toString().matches("\\d+\\.x"); Files.delete(f); return ok + " " + f.getParent().equals(root); });
        Path f = root.resolve("f.txt");
        Path missing = root.resolve("missing");
        t("write bytes", () -> Files.write(f, "h\u00e9llo".getBytes(StandardCharsets.UTF_8)) + " " + Files.size(f));
        t("readAllBytes", () -> new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
        t("write APPEND", () -> { Files.write(f, "!".getBytes(), StandardOpenOption.APPEND); return text(f); });
        t("write CREATE_NEW existing", () -> Files.write(f, new byte[1], StandardOpenOption.CREATE_NEW));
        t("write APPEND missing", () -> Files.write(missing, new byte[1], StandardOpenOption.APPEND));
        t("write WRITE missing", () -> Files.write(missing, new byte[1], StandardOpenOption.WRITE));
        t("write READ", () -> Files.write(root.resolve("r"), new byte[1], StandardOpenOption.READ));
        t("write APPEND+TRUNCATE", () -> Files.write(root.resolve("r"), new byte[1], StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING));
        t("write lines", () -> { Files.write(f, Arrays.asList("a", "", "b\u00e9")); return text(f) + " " + (Files.size(f) - 3 * System.lineSeparator().length()); });
        t("write lines latin1", () -> { Files.write(f, Arrays.asList("\u00e9"), StandardCharsets.ISO_8859_1); return Files.size(f) - System.lineSeparator().length(); });
        t("write lines unmappable", () -> Files.write(f, Arrays.asList("\u20ac"), StandardCharsets.ISO_8859_1));
        t("readAllLines", () -> { Files.write(f, "a\nb\r\nc\rd\r\n\ne".getBytes()); return Files.readAllLines(f); });
        t("readAllLines trailing", () -> { Files.write(f, "a\n".getBytes()); return Files.readAllLines(f) + " " + Files.readAllLines(f).size(); });
        t("readAllLines empty", () -> { Files.write(f, new byte[0]); return Files.readAllLines(f); });
        t("readAllLines malformed", () -> { Files.write(f, new byte[]{'a', (byte) 0xC3, '\n'}); return Files.readAllLines(f); });
        t("readAllLines latin1", () -> { Files.write(f, new byte[]{(byte) 0xE9}); return Files.readAllLines(f, StandardCharsets.ISO_8859_1).get(0).equals("\u00e9"); });
        t("readAllBytes missing", () -> Files.readAllBytes(missing));
        t("readAllBytes dir", () -> { try { Files.readAllBytes(root); return "?"; } catch (IOException e) { return "IOException"; } });
        t("newBufferedReader malformed", () -> { Files.write(f, new byte[]{'a', (byte) 0xFF}); try (BufferedReader r = Files.newBufferedReader(f)) { return r.readLine(); } });
        t("newBufferedWriter", () -> { try (BufferedWriter w = Files.newBufferedWriter(f)) { w.write("x\u20ac"); w.newLine(); } return text(f); });
        t("newBufferedWriter unmappable", () -> { try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.US_ASCII)) { w.write("\u00e9"); } return "?"; });
        t("newBufferedWriter APPEND", () -> { try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) { w.write("z"); } return text(f); });
        t("newInputStream missing", () -> Files.newInputStream(missing));
        t("newOutputStream in missing dir", () -> Files.newOutputStream(root.resolve("nope/x")));
        t("newInputStream read", () -> { try (InputStream in = Files.newInputStream(f)) { return in.read() + " " + in.available() + " " + in.skip(100) + " " + in.read(); } });
        t("newInputStream 10 bytes", () -> { Path g = root.resolve("ten"); Files.write(g, new byte[10]); try (InputStream in = Files.newInputStream(g)) { return in.available() + " " + in.read() + " " + in.available() + " " + in.skip(4) + " " + in.available() + " " + in.skip(100) + " " + in.read() + " " + in.markSupported(); } });
        t("newInputStream closed", () -> { InputStream in = Files.newInputStream(f); in.close(); in.close(); try { in.read(); return "?"; } catch (IOException e) { return "IOException"; } });
        t("copy(InputStream,Path)", () -> Files.copy(new ByteArrayInputStream(new byte[5]), root.resolve("c1")));
        t("copy(InputStream,Path) existing", () -> Files.copy(new ByteArrayInputStream(new byte[5]), root.resolve("c1")));
        t("copy(InputStream,Path) replace", () -> Files.copy(new ByteArrayInputStream(new byte[3]), root.resolve("c1"), StandardCopyOption.REPLACE_EXISTING) + " " + Files.size(root.resolve("c1")));
        t("copy(Path,OutputStream)", () -> Files.copy(f, new ByteArrayOutputStream()));
        t("copy", () -> rel(Files.copy(f, root.resolve("c2"))) + " " + text(root.resolve("c2")));
        t("copy existing", () -> Files.copy(f, root.resolve("c2")));
        t("copy missing", () -> Files.copy(missing, root.resolve("c3")));
        t("copy replace", () -> rel(Files.copy(root.resolve("c1"), root.resolve("c2"), StandardCopyOption.REPLACE_EXISTING)) + " " + Files.size(root.resolve("c2")));
        t("copy onto self", () -> rel(Files.copy(f, f)));
        t("copy into missing dir", () -> Files.copy(f, root.resolve("nope/c")));
        t("copy attributes", () -> { Files.setLastModifiedTime(f, FileTime.fromMillis(1234567890000L)); Files.copy(f, root.resolve("c4"), StandardCopyOption.COPY_ATTRIBUTES); return Files.getLastModifiedTime(root.resolve("c4")); });
        t("move", () -> rel(Files.move(root.resolve("c4"), root.resolve("m1"))) + " " + Files.exists(root.resolve("c4")) + " " + Files.exists(root.resolve("m1")));
        t("move existing", () -> Files.move(root.resolve("m1"), root.resolve("c2")));
        t("move replace", () -> rel(Files.move(root.resolve("m1"), root.resolve("c2"), StandardCopyOption.REPLACE_EXISTING)) + " " + Files.exists(root.resolve("m1")));
        t("move missing", () -> Files.move(missing, root.resolve("m2")));
        t("move atomic", () -> rel(Files.move(root.resolve("c2"), root.resolve("m3"), StandardCopyOption.ATOMIC_MOVE)));
        t("createDirectory", () -> rel(Files.createDirectory(root.resolve("d"))) + " " + Files.isDirectory(root.resolve("d")));
        t("createDirectory existing", () -> Files.createDirectory(root.resolve("d")));
        t("createDirectory over file", () -> Files.createDirectory(f));
        t("createDirectory missing parent", () -> Files.createDirectory(root.resolve("x/y")));
        t("createDirectories", () -> rel(Files.createDirectories(root.resolve("x/y/z"))) + " " + rel(Files.createDirectories(root.resolve("x/y/z"))));
        t("createDirectories is file", () -> Files.createDirectories(f));
        t("createFile", () -> rel(Files.createFile(root.resolve("d/e"))) + " " + Files.size(root.resolve("d/e")));
        t("createFile existing", () -> Files.createFile(root.resolve("d/e")));
        t("delete non-empty", () -> { Files.delete(root.resolve("d")); return "?"; });
        t("delete missing", () -> { Files.delete(missing); return "?"; });
        t("deleteIfExists", () -> Files.deleteIfExists(root.resolve("d/e")) + " " + Files.deleteIfExists(root.resolve("d/e")) + " " + Files.deleteIfExists(root.resolve("d")));
        t("size missing", () -> Files.size(missing));
        t("tests", () -> Files.exists(f) + " " + Files.notExists(f) + " " + Files.exists(missing) + " " + Files.notExists(missing) + " " + Files.isRegularFile(f) + " " + Files.isDirectory(f)
            + " " + Files.isDirectory(root) + " " + Files.isReadable(f) + " " + Files.isWritable(f) + " " + Files.isReadable(missing) + " " + Files.isSymbolicLink(f) + " " + Files.isRegularFile(missing));
        t("isSameFile", () -> Files.isSameFile(f, root.resolve("x/../f.txt")) + " " + Files.isSameFile(missing, missing));
        t("isSameFile missing", () -> Files.isSameFile(f, missing));
        t("lastModifiedTime", () -> { Files.setLastModifiedTime(f, FileTime.fromMillis(2000)); return Files.getLastModifiedTime(f) + " " + f.toFile().lastModified(); });
        t("lastModifiedTime missing", () -> Files.getLastModifiedTime(missing));
        t("basic attributes", () -> { BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class); return a.size() + " " + a.isRegularFile() + " " + a.isDirectory() + " " + a.isSymbolicLink() + " " + a.isOther() + " " + a.lastModifiedTime(); });
        t("basic attributes dir", () -> { BasicFileAttributes a = Files.readAttributes(root, BasicFileAttributes.class); return a.isRegularFile() + " " + a.isDirectory() + " " + a.isOther(); });
        t("attributes missing", () -> Files.readAttributes(missing, BasicFileAttributes.class));
        t("attribute map", () -> new TreeMap<>(Files.readAttributes(f, "size,isDirectory,lastModifiedTime")));
        t("attribute map basic:", () -> new TreeMap<>(Files.readAttributes(f, "basic:size,isRegularFile")));
        t("attribute bad name", () -> Files.readAttributes(f, "nosuch"));
        t("attribute bad view", () -> Files.readAttributes(f, "noview:size"));
        t("getAttribute", () -> Files.getAttribute(f, "size") + " " + Files.getAttribute(f, "basic:isDirectory") + " " + Files.getAttribute(f, "lastModifiedTime"));
        t("setAttribute", () -> { Files.setAttribute(f, "lastModifiedTime", FileTime.fromMillis(6000)); return Files.getLastModifiedTime(f).toMillis(); });
        t("FileTime", () -> FileTime.fromMillis(0) + " " + FileTime.fromMillis(1234567890123L) + " " + FileTime.fromMillis(-1) + " " + FileTime.from(90, java.util.concurrent.TimeUnit.SECONDS) + " " + FileTime.fromMillis(1500).toMillis());
        t("FileTime compare", () -> FileTime.fromMillis(1000).equals(FileTime.from(1, java.util.concurrent.TimeUnit.SECONDS)) + " " + FileTime.fromMillis(1).compareTo(FileTime.fromMillis(2)) + " " + (FileTime.fromMillis(1000).hashCode() == FileTime.from(1, java.util.concurrent.TimeUnit.SECONDS).hashCode()));
        t("FileTime.toString fraction", () -> FileTime.from(1500, java.util.concurrent.TimeUnit.MICROSECONDS) + " " + FileTime.from(10, java.util.concurrent.TimeUnit.NANOSECONDS));
        t("exception messages", () -> new NoSuchFileException("a").getMessage() + " | " + new NoSuchFileException("a", "b", "why").getMessage() + " | " + new FileAlreadyExistsException("a", null, "r").getMessage()
            + " | " + new DirectoryNotEmptyException("d").getMessage() + " | " + new FileSystemException("a", "b", null).getMessage() + " | " + new NotDirectoryException("n").getReason() + " | " + new AccessDeniedException("x").getOtherFile());
        System.out.println("cleanup: " + delete(root.toFile()) + " " + Files.exists(root));
        System.out.println("done");
    }

    static boolean delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        return f.delete();
    }
}
