package stream;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Whether a file stream really closes its file, observed by renaming the directory that holds
 * the file: Windows refuses that while the file is open.
 *
 * A system that lets a directory be renamed under an open file (LazyOS may) cannot show the
 * difference. The test finds out first, with a plain input stream, whether the check means
 * anything here. When it does not, every line prints what a correct implementation prints on
 * Windows, so the golden is the same everywhere and the test only bites where renaming is
 * refused. It is the one test of the open/closed state of a file; it is not vacuous on Windows.
 */
public class FileCloseRename {
    static Path root, file;
    static boolean enforced;
    static int scenario;
    /** Every file stream the test opens, so that all are closed before the temp directory goes. */
    static final List<Stream<String>> opened = new ArrayList<>();

    /** A new directory with its own file: a stream left open in one scenario cannot touch the next. */
    static void use() throws IOException {
        Path dir = root.resolve("s" + ++scenario);
        Files.createDirectories(dir);
        file = dir.resolve("f.txt");
        Files.write(file, Arrays.asList("one", "two", "three"));
    }
    /** True when the directory of the current file cannot be renamed now. */
    static boolean locked() throws IOException {
        Path dir = file.getParent();
        Path other = root.resolve("moved");
        try {
            Files.move(dir, other);
        } catch (FileSystemException e) {
            return true;
        }
        Files.move(other, dir);
        return false;
    }
    /** Print what the check saw, or what a correct run on Windows sees where the check cannot tell. */
    static void check(String label, boolean expectLocked) throws IOException {
        boolean seen = enforced ? locked() : expectLocked;
        System.out.println(label + ": " + (seen ? "open" : "closed"));
    }
    static Stream<String> lines(Path p) {
        try { Stream<String> s = Files.lines(p); opened.add(s); return s; } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazyrename");
        try { run(); } finally { for (Stream<String> s : opened) s.close(); rm(root); }
    }
    static void rm(Path p) throws IOException {
        if (Files.isDirectory(p)) try (DirectoryStream<Path> d = Files.newDirectoryStream(p)) { for (Path c : d) rm(c); }
        Files.delete(p);
    }

    static void run() throws Exception {
        // the control: a plainly open file
        use();
        try (InputStream in = Files.newInputStream(file)) { enforced = locked(); }
        System.out.println("control open file: open");
        System.out.println("control closed file: " + (locked() ? "open" : "closed"));
        use();
        Stream<String> s = lines(file);
        check("lines fresh", true);
        s.close();
        check("lines closed", false);

        use();
        s = lines(file);
        s.findFirst();
        check("lines findFirst, not closed", true);
        s.close();
        check("lines findFirst, closed", false);

        use();
        s = lines(file);
        s.forEach(x -> { });
        check("lines exhausted, not closed", true);
        s.close();
        check("lines exhausted, closed", false);

        use();
        s = lines(file);
        s.count();
        check("lines count, not closed", true);
        s.close();
        s.close();
        check("lines closed twice", false);

        use();
        try (Stream<String> t = lines(file)) { t.count(); check("lines in try", true); }
        check("lines after try", false);

        use();
        s = lines(file);
        try { s.map(x -> { throw new IllegalStateException("m"); }).count(); } catch (IllegalStateException e) { }
        check("lines after exception, not closed", true);
        s.close();
        check("lines after exception, closed", false);

        use();
        s = lines(file);
        Iterator<String> it = s.iterator();
        it.next();
        check("lines iterator, not closed", true);
        while (it.hasNext()) it.next();
        check("lines iterator exhausted, not closed", true);
        s.close();
        check("lines iterator closed", false);

        use();
        s = lines(file);
        Spliterator<String> sp = s.spliterator();
        sp.tryAdvance(x -> { });
        check("lines spliterator, not closed", true);
        s.close();
        check("lines spliterator closed", false);

        use();
        s = lines(file).onClose(() -> System.out.println("handler ran"));
        s.close();
        check("lines onClose closed", false);

        use();
        s = lines(file).onClose(() -> { throw new IllegalStateException("h"); });
        try { s.close(); } catch (IllegalStateException e) { System.out.println("handler threw"); }
        check("lines onClose threw, closed", false);

        use();
        s = lines(file).map(x -> x).filter(x -> true);
        check("lines mapped fresh", true);
        s.close();
        check("lines mapped closed", false);

        // flatMap closes the inner stream itself
        Stream<Path> paths = Stream.of(file, file);
        paths.flatMap(p -> lines(p)).count();
        check("flatMap count", false);

        use();
        Stream.of(file, file).flatMap(p -> lines(p)).findFirst();
        check("flatMap findFirst", false);

        use();
        Stream.of(file, file).flatMap(p -> lines(p)).limit(2).forEach(x -> { });
        check("flatMap limit", false);

        use();
        Stream.of(file, file).flatMap(p -> lines(p)).anyMatch(x -> x.equals("two"));
        check("flatMap anyMatch", false);

        use();
        try { Stream.of(file).flatMap(p -> lines(p)).peek(x -> { throw new IllegalStateException("p"); }).count(); } catch (IllegalStateException e) { }
        check("flatMap peek throws", false);

        use();
        try { Stream.of(file).flatMap(p -> lines(p).map(x -> { throw new IllegalStateException("m"); })).count(); } catch (IllegalStateException e) { }
        check("flatMap inner throws", false);

        use();
        Iterator<String> fi = Stream.of(file, file).flatMap(p -> lines(p)).iterator();
        fi.next();
        check("flatMap iterator after first", false);

        use();
        Spliterator<String> fsp = Stream.of(file, file).flatMap(p -> lines(p)).spliterator();
        fsp.tryAdvance(x -> { });
        check("flatMap spliterator after first", false);

        use();
        Stream.of(file).flatMap(p -> Stream.of(p).flatMap(q -> lines(q))).count();
        check("nested flatMap", false);

        use();
        Stream.of(file).flatMap(p -> Stream.of(p, p).flatMap(q -> lines(q))).findFirst();
        check("nested flatMap findFirst", false);

        use();
        Stream.of(file).flatMap(p -> Stream.of(p, p).flatMap(q -> lines(q))).limit(1).count();
        check("nested flatMap limit", false);

        use();
        Stream.of(file, file).flatMap(p -> lines(p)).sorted().limit(1).count();
        check("flatMap sorted limit", false);

        use();
        Stream.of(file).map(p -> lines(p)).forEach(x -> { });
        check("map to stream, never run, not closed", true);

        use();
        Stream<String> kept = lines(file);
        Stream.of(file).flatMap(p -> kept).count();
        check("flatMap of an existing stream", false);

        use();
        Stream<String> used = lines(file);
        used.count();
        try { Stream.of(file).flatMap(p -> used).count(); } catch (IllegalStateException e) { System.out.println("used inner: ISE"); }
        check("flatMap closes a used inner stream", false);
        used.close();

        use();
        lines(file).flatMap(l -> Stream.of(l, l)).count();
        check("flatMap over the file itself, not closed", true);

        use();
        s = lines(file);
        s.onClose(() -> { });
        check("lines onClose registered", true);
        s.close();
        check("lines onClose registered, closed", false);

        use();
        s = lines(file).parallel();
        s.count();
        check("lines parallel, not closed", true);
        s.close();
        check("lines parallel, closed", false);

        use();
        s = lines(file).sequential().unordered();
        check("lines unordered", true);
        s.close();
        check("lines unordered, closed", false);

        // two streams of one file: one closed, one open
        Stream<String> a = lines(file), b = lines(file);
        a.close();
        check("two streams, one closed", true);
        b.close();
        check("two streams, both closed", false);

        // BufferedReader.lines does not own the reader
        BufferedReader r = Files.newBufferedReader(file);
        r.lines().count();
        check("reader lines exhausted", true);
        r.lines().close();
        check("reader lines closed", true);
        r.close();
        check("reader closed", false);
    }
}
