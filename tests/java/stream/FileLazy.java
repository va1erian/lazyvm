package stream;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import java.util.stream.*;

/**
 * Files.list, walk, find and lines: flags, when the directory is read, and what an iterator or
 * spliterator does after the stream was closed. Paths print relative to a temp directory.
 * Symbolic links are not covered, see docs/design/streams.md H7.
 */
public class FileLazy {
    static Path root;
    static String clean(String s) { return s == null ? null : s.replace(root.toString(), "<tmp>").replace(File.separatorChar, '/'); }
    static void t(String label, Callable<Object> c) {
        String r;
        try { r = String.valueOf(c.call()); }
        catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + clean(e.getMessage()));
            if (e.getCause() != null) r += " <- " + e.getCause().getClass().getName() + (e.getCause().getMessage() == null ? "" : ": " + clean(e.getCause().getMessage()));
        }
        System.out.println(label + ": " + r);
    }
    static String names(int c) {
        String[] n = {"ORDERED", "DISTINCT", "SORTED", "SIZED", "NONNULL", "IMMUTABLE", "CONCURRENT", "SUBSIZED"};
        int[] v = {Spliterator.ORDERED, Spliterator.DISTINCT, Spliterator.SORTED, Spliterator.SIZED, Spliterator.NONNULL, Spliterator.IMMUTABLE, Spliterator.CONCURRENT, Spliterator.SUBSIZED};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length; i++) if ((c & v[i]) != 0) sb.append(sb.length() == 0 ? "" : "|").append(n[i]);
        return sb.length() == 0 ? "-" : sb.toString();
    }
    /** The flags, with the size only when it is exact: an estimate may differ between systems. */
    static String info(Spliterator<?> s) { return names(s.characteristics()) + " exact=" + s.getExactSizeIfKnown(); }
    static String rel(Path p) { String s = root.relativize(p).toString().replace(File.separatorChar, '/'); return s.isEmpty() ? "." : s; }
    static List<String> names(Stream<Path> s) { try (Stream<Path> x = s) { return x.map(FileLazy::rel).sorted().collect(Collectors.toList()); } }
    static Path text(String name, String s) throws IOException { Path p = root.resolve(name); Files.createDirectories(p.getParent()); Files.write(p, s.getBytes(StandardCharsets.UTF_8)); return p; }
    static void rm(Path p) throws IOException {
        if (Files.isDirectory(p)) try (DirectoryStream<Path> d = Files.newDirectoryStream(p)) { for (Path c : d) rm(c); }
        Files.delete(p);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazyfiles");
        try { run(); } finally { rm(root); }
    }

    static void run() throws Exception {
        Path f = text("f.txt", "x\ny\n");
        text("d/a", "a"); text("d/b", "b"); text("d/c", "c"); text("d/sub/s", "s");

        // flags: no size, no order promise except what lines gives
        t("list flags", () -> { try (Stream<Path> s = Files.list(root.resolve("d"))) { return info(s.spliterator()); } });
        t("walk flags", () -> { try (Stream<Path> s = Files.walk(root.resolve("d"))) { return info(s.spliterator()); } });
        t("walk depth flags", () -> { try (Stream<Path> s = Files.walk(root.resolve("d"), 1)) { return info(s.spliterator()); } });
        t("find flags", () -> { try (Stream<Path> s = Files.find(root.resolve("d"), 3, (p, a) -> true)) { return info(s.spliterator()); } });
        t("lines flags", () -> { try (Stream<String> s = Files.lines(f)) { return info(s.spliterator()); } });
        t("lines flags charset", () -> { try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) { return info(s.spliterator()); } });
        t("lines flags ISO", () -> { try (Stream<String> s = Files.lines(f, StandardCharsets.ISO_8859_1)) { return info(s.spliterator()); } });
        t("list is not sized", () -> { try (Stream<Path> s = Files.list(root.resolve("d"))) { return s.spliterator().hasCharacteristics(Spliterator.SIZED); } });
        t("walk after map flags", () -> { try (Stream<String> s = Files.walk(root.resolve("d")).map(Path::toString)) { return info(s.spliterator()); } });
        t("list filter flags", () -> { try (Stream<Path> s = Files.list(root.resolve("d")).filter(p -> true)) { return info(s.spliterator()); } });

        // the directory is read when the stream runs, not at the call
        t("list sees deletion after call", () -> {
            Path dir = root.resolve("lazy1"); text("lazy1/a", "a"); text("lazy1/b", "b"); text("lazy1/c", "c");
            Stream<Path> s = Files.list(dir);
            Files.delete(dir.resolve("b"));
            return names(s);
        });
        t("list sees creation after call", () -> {
            Path dir = root.resolve("lazy2"); text("lazy2/a", "a");
            Stream<Path> s = Files.list(dir);
            text("lazy2/z", "z");
            return names(s);
        });
        t("list sees deletion of all after call", () -> {
            Path dir = root.resolve("lazy3"); text("lazy3/a", "a"); text("lazy3/b", "b");
            Stream<Path> s = Files.list(dir);
            Files.delete(dir.resolve("a")); Files.delete(dir.resolve("b"));
            return names(s);
        });
        t("walk sees changes after call", () -> {
            Path dir = root.resolve("lazy5"); text("lazy5/a", "a"); text("lazy5/c", "c");
            Stream<Path> s = Files.walk(dir);
            Files.delete(dir.resolve("c")); text("lazy5/y", "y");
            return names(s);
        });
        t("walk sees new subdirectory", () -> {
            Path dir = root.resolve("lazy6"); text("lazy6/a", "a");
            Stream<Path> s = Files.walk(dir);
            text("lazy6/sub/q", "q");
            return names(s);
        });
        t("find sees changes after call", () -> {
            Path dir = root.resolve("lazy7"); text("lazy7/a", "a"); text("lazy7/c", "c");
            Stream<Path> s = Files.find(dir, 2, (p, a) -> a.isRegularFile());
            Files.delete(dir.resolve("c")); text("lazy7/y", "y");
            return names(s);
        });
        t("lines sees content after call", () -> {
            Path p = text("lazy8.txt", "old\n");
            Stream<String> s = Files.lines(p);
            Files.write(p, "new1\nnew2\n".getBytes(StandardCharsets.UTF_8));
            try (Stream<String> x = s) { return x.collect(Collectors.toList()); }
        });
        t("list laziness with iterator", () -> {
            Path dir = root.resolve("lazy10"); text("lazy10/a", "a"); text("lazy10/b", "b");
            Stream<Path> s = Files.list(dir);
            Iterator<Path> it = s.iterator();
            Files.delete(dir.resolve("b"));
            List<String> got = new ArrayList<>();
            while (it.hasNext()) got.add(rel(it.next()));
            s.close();
            Collections.sort(got);
            return got;
        });

        // iterators and spliterators after close()
        t("list iterator after close", () -> { Stream<Path> s = Files.list(root.resolve("d")); Iterator<Path> it = s.iterator(); s.close(); return it.hasNext(); });
        t("list iterator after hasNext and close", () -> {
            Stream<Path> s = Files.list(root.resolve("d")); Iterator<Path> it = s.iterator(); boolean h = it.hasNext(); s.close();
            return h + " " + it.hasNext() + " " + (it.hasNext() ? rel(it.next()).startsWith("d/") : "-");
        });
        t("list iterator drained after close", () -> {
            Stream<Path> s = Files.list(root.resolve("d")); Iterator<Path> it = s.iterator(); it.next(); s.close();
            int n = 1; try { while (it.hasNext()) { it.next(); n++; } } catch (RuntimeException e) { return n + " " + e.getClass().getName(); }
            return n;
        });
        t("list spliterator after close", () -> { Stream<Path> s = Files.list(root.resolve("d")); Spliterator<Path> sp = s.spliterator(); s.close(); return sp.tryAdvance(p -> {}); });
        t("list spliterator forEachRemaining after close", () -> { Stream<Path> s = Files.list(root.resolve("d")); Spliterator<Path> sp = s.spliterator(); s.close(); int[] n = {0}; sp.forEachRemaining(p -> n[0]++); return n[0]; });
        t("walk iterator after close", () -> { Stream<Path> s = Files.walk(root.resolve("d")); Iterator<Path> it = s.iterator(); s.close(); return it.hasNext(); });
        t("walk iterator after hasNext and close", () -> {
            Stream<Path> s = Files.walk(root.resolve("d")); Iterator<Path> it = s.iterator(); it.hasNext(); s.close();
            int n = 0; while (it.hasNext()) { it.next(); n++; } return n;
        });
        t("walk spliterator after close", () -> { Stream<Path> s = Files.walk(root.resolve("d")); Spliterator<Path> sp = s.spliterator(); s.close(); return sp.tryAdvance(p -> {}); });
        t("find iterator after close", () -> { Stream<Path> s = Files.find(root.resolve("d"), 2, (p, a) -> true); Iterator<Path> it = s.iterator(); s.close(); return it.hasNext(); });
        t("lines iterator after close", () -> { Stream<String> s = Files.lines(f); Iterator<String> it = s.iterator(); s.close(); return it.hasNext(); });
        t("lines iterator after hasNext and close", () -> { Stream<String> s = Files.lines(f); Iterator<String> it = s.iterator(); it.hasNext(); s.close(); return it.next() + " " + it.hasNext(); });
        t("lines iterator drained after close", () -> { Stream<String> s = Files.lines(f); Iterator<String> it = s.iterator(); it.next(); s.close(); return it.hasNext() + ""; });
        t("lines spliterator after close", () -> { Stream<String> s = Files.lines(f); Spliterator<String> sp = s.spliterator(); s.close(); return sp.tryAdvance(x -> {}); });
        t("lines spliterator after tryAdvance and close", () -> { Stream<String> s = Files.lines(f); Spliterator<String> sp = s.spliterator(); sp.tryAdvance(x -> {}); s.close(); return sp.tryAdvance(x -> {}); });
        t("lines after close", () -> { Stream<String> s = Files.lines(f); s.close(); return s.count(); });
        t("lines mapped after source close", () -> { Stream<String> s = Files.lines(f); Stream<String> m = s.map(x -> x); s.close(); return m.count(); });
        t("lines close during forEach", () -> { Stream<String> s = Files.lines(f); List<String> got = new ArrayList<>(); s.forEach(x -> { got.add(x); s.close(); }); return got; });
        t("lines close inside iterator loop", () -> {
            Stream<String> s = Files.lines(f); Iterator<String> it = s.iterator(); List<String> got = new ArrayList<>();
            while (it.hasNext()) { got.add(it.next()); s.close(); }
            return got;
        });
        t("lines close then use iterator forEachRemaining", () -> {
            Stream<String> s = Files.lines(f); Iterator<String> it = s.iterator(); s.close();
            List<String> got = new ArrayList<>(); it.forEachRemaining(got::add); return got;
        });
        t("lines onClose runs once", () -> { StringBuilder sb = new StringBuilder(); Stream<String> s = Files.lines(f).onClose(() -> sb.append("h")); s.close(); s.close(); return sb; });
        t("lines onClose throws, file still closed", () -> {
            Stream<String> s = Files.lines(f).onClose(() -> { throw new IllegalStateException("h"); });
            try { s.close(); } catch (IllegalStateException e) { }
            Iterator<String> it = null;
            try { return s.count(); } catch (IllegalStateException e) { return e.getMessage(); }
        });
        t("list close twice then iterate", () -> { Stream<Path> s = Files.list(root.resolve("d")); s.close(); s.close(); return s.iterator().hasNext(); });
    }
}
