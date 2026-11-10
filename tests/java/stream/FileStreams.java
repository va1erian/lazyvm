package stream;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;
import java.util.stream.*;

/** Files.lines, list, walk and find, and BufferedReader.lines. Paths in messages print as <tmp>. */
public class FileStreams {
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
    static String rel(Path p) { String s = root.relativize(p).toString().replace(File.separatorChar, '/'); return s.isEmpty() ? "." : s; }
    static List<String> rels(Stream<Path> s) { try (Stream<Path> x = s) { return x.map(FileStreams::rel).sorted().collect(Collectors.toList()); } }
    static Path write(String name, byte[] data) throws IOException { Path p = root.resolve(name); Files.createDirectories(p.getParent()); Files.write(p, data); return p; }
    static Path text(String name, String s) throws IOException { return write(name, s.getBytes(StandardCharsets.UTF_8)); }
    static BufferedReader br(String s) { return new BufferedReader(new StringReader(s)); }
    static void rm(Path p) throws IOException {
        if (Files.isDirectory(p)) try (DirectoryStream<Path> d = Files.newDirectoryStream(p)) { for (Path c : d) rm(c); }
        Files.delete(p);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazystream");
        try { run(); } finally { rm(root); }
    }

    static void run() throws Exception {
        Path abc = text("abc.txt", "a\nb\r\nc\rd\n\ne");
        Path trail = text("trail.txt", "x\ny\n");
        Path empty = text("empty.txt", "");
        Path nl = text("nl.txt", "\n");
        Path crlf = text("crlf.txt", "p\r\nq\r\n");
        Path latin = write("latin.txt", new byte[] {'c', 'a', 'f', (byte) 0xE9, '\n', 'z'});
        Path utf = text("utf.txt", "café\n€");
        Path bad = write("bad.txt", new byte[] {'o', 'k', '\n', (byte) 0xC3, '\n', 'z'});
        text("tree/a.txt", "1");
        text("tree/b.java", "22");
        text("tree/sub/c.txt", "333");
        text("tree/sub/deep/d.txt", "4444");
        Files.createDirectories(root.resolve("tree/emptydir"));
        Path tree = root.resolve("tree");

        // Files.lines
        t("lines", () -> Files.lines(abc).collect(Collectors.toList()));
        t("lines trailing newline", () -> Files.lines(trail).collect(Collectors.toList()));
        t("lines empty", () -> Files.lines(empty).count());
        t("lines only newline", () -> Files.lines(nl).collect(Collectors.toList()).size());
        t("lines crlf", () -> Files.lines(crlf).collect(Collectors.toList()));
        t("lines utf8", () -> Files.lines(utf).collect(Collectors.toList()));
        t("lines latin1", () -> Files.lines(latin, StandardCharsets.ISO_8859_1).collect(Collectors.toList()));
        t("lines ascii", () -> Files.lines(latin, StandardCharsets.US_ASCII).collect(Collectors.toList()));
        t("lines utf8 on latin1 bytes", () -> Files.lines(latin).collect(Collectors.toList()));
        t("lines utf8 error is lazy", () -> { Stream<String> s = Files.lines(bad); return "opened " + s.findFirst(); });
        t("lines utf8 error late", () -> Files.lines(bad).collect(Collectors.toList()));
        t("lines utf8 error after limit", () -> Files.lines(bad).limit(1).collect(Collectors.toList()));
        t("lines utf8 error after skip", () -> Files.lines(bad).skip(2).collect(Collectors.toList()));
        t("lines missing", () -> Files.lines(root.resolve("missing.txt")));
        t("lines null path", () -> Files.lines(null));
        t("lines null charset", () -> Files.lines(abc, null));
        t("lines map", () -> Files.lines(abc).map(String::length).collect(Collectors.toList()));
        t("lines count", () -> Files.lines(abc).count());
        t("lines sorted", () -> Files.lines(abc).sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
        t("lines is single use", () -> { Stream<String> s = Files.lines(abc); s.count(); return s.count(); });
        t("lines close then delete", () -> { Path p = text("del1.txt", "q\nr"); try (Stream<String> s = Files.lines(p)) { s.findFirst(); } Files.delete(p); return Files.exists(p); });
        t("lines exhausted then delete", () -> { Path p = text("del2.txt", "q\nr"); Files.lines(p).count(); Files.delete(p); return Files.exists(p); });
        t("lines onClose", () -> { StringBuilder sb = new StringBuilder(); try (Stream<String> s = Files.lines(abc).onClose(() -> sb.append("closed"))) { s.count(); sb.append("counted "); } return sb; });
        t("lines close twice", () -> { Stream<String> s = Files.lines(abc); s.close(); s.close(); return "ok"; });
        t("lines close without use", () -> { Stream<String> s = Files.lines(abc); s.close(); return s.count(); });
        t("lines iterator", () -> { Iterator<String> it = Files.lines(abc).iterator(); return it.next() + it.next() + it.hasNext(); });
        t("lines parallel", () -> Files.lines(abc).parallel().map(String::toUpperCase).collect(Collectors.toList()));
        t("lines characteristics", () -> Files.lines(abc).spliterator().characteristics() & (Spliterator.SIZED | Spliterator.SUBSIZED));
        t("lines ordered", () -> (Files.lines(abc).spliterator().characteristics() & Spliterator.ORDERED) != 0);
        t("lines collect joining", () -> Files.lines(trail).collect(Collectors.joining("+")));
        t("lines word count", () -> Files.lines(abc).flatMap(l -> Stream.of(l.split(""))).filter(x -> !x.isEmpty()).count());

        // Files.list
        t("list", () -> rels(Files.list(tree)));
        t("list is shallow", () -> rels(Files.list(tree)).size());
        t("list empty dir", () -> rels(Files.list(tree.resolve("emptydir"))));
        t("list missing", () -> Files.list(root.resolve("nope")));
        t("list on file", () -> Files.list(abc));
        t("list null", () -> Files.list(null));
        t("list resolves against dir", () -> { try (Stream<Path> s = Files.list(tree)) { return s.allMatch(p -> p.getParent().equals(tree)); } });
        t("list close twice", () -> { Stream<Path> s = Files.list(tree); s.close(); s.close(); return "ok"; });
        t("list onClose", () -> { StringBuilder sb = new StringBuilder(); try (Stream<Path> s = Files.list(tree).onClose(() -> sb.append("closed"))) { s.count(); } return sb; });
        t("list count", () -> { try (Stream<Path> s = Files.list(tree)) { return s.count(); } });
        t("list filter dirs", () -> { try (Stream<Path> s = Files.list(tree)) { return s.filter(Files::isDirectory).map(FileStreams::rel).sorted().collect(Collectors.toList()); } });
        t("list is single use", () -> { Stream<Path> s = Files.list(tree); s.close(); return s.count(); });
        t("list iterator", () -> { try (Stream<Path> s = Files.list(tree)) { Iterator<Path> it = s.iterator(); int n = 0; while (it.hasNext()) { it.next(); n++; } return n; } });

        // Files.walk
        t("walk", () -> rels(Files.walk(tree)));
        t("walk depth 0", () -> rels(Files.walk(tree, 0)));
        t("walk depth 1", () -> rels(Files.walk(tree, 1)));
        t("walk depth 2", () -> rels(Files.walk(tree, 2)));
        t("walk depth MAX", () -> rels(Files.walk(tree, Integer.MAX_VALUE)).size());
        t("walk depth negative", () -> Files.walk(tree, -1));
        t("walk file", () -> rels(Files.walk(abc)));
        t("walk file depth 0", () -> rels(Files.walk(abc, 0)));
        t("walk missing", () -> Files.walk(root.resolve("nope")));
        t("walk null", () -> Files.walk(null));
        t("walk null option", () -> Files.walk(tree, 1, (FileVisitOption) null));
        t("walk follow links option", () -> rels(Files.walk(tree, 1, FileVisitOption.FOLLOW_LINKS)));
        t("walk parent before child", () -> { try (Stream<Path> s = Files.walk(tree)) { List<Path> l = s.collect(Collectors.toList()); for (int i = 0; i < l.size(); i++) for (int j = 0; j < i; j++) if (l.get(j).startsWith(l.get(i)) && !l.get(j).equals(l.get(i))) return "child before parent"; return "ok " + l.get(0).equals(tree); } });
        t("walk is lazy ordered start", () -> { try (Stream<Path> s = Files.walk(tree)) { return rel(s.findFirst().get()); } });
        t("walk files only", () -> { try (Stream<Path> s = Files.walk(tree)) { return s.filter(Files::isRegularFile).map(FileStreams::rel).sorted().collect(Collectors.toList()); } });
        t("walk sizes", () -> { try (Stream<Path> s = Files.walk(tree)) { return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum(); } });
        t("walk names", () -> { try (Stream<Path> s = Files.walk(tree)) { return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted().collect(Collectors.toList()); } });
        t("walk onClose", () -> { StringBuilder sb = new StringBuilder(); try (Stream<Path> s = Files.walk(tree).onClose(() -> sb.append("closed"))) { s.count(); } return sb; });
        t("walk close then delete", () -> { Path d = root.resolve("w"); Files.createDirectories(d.resolve("x/y")); try (Stream<Path> s = Files.walk(d)) { s.findFirst(); } rm(d); return Files.exists(d); });
        t("walk consumed then delete", () -> { Path d = root.resolve("w2"); Files.createDirectories(d.resolve("x/y")); Files.walk(d).count(); rm(d); return Files.exists(d); });
        t("walk limit", () -> { try (Stream<Path> s = Files.walk(tree)) { return s.limit(2).count(); } });
        t("walk skip", () -> { try (Stream<Path> s = Files.walk(tree)) { return s.skip(1).count(); } });
        t("walk iterator", () -> { try (Stream<Path> s = Files.walk(tree)) { Iterator<Path> it = s.iterator(); int n = 0; while (it.hasNext()) { it.next(); n++; } return n; } });
        t("walk depth first", () -> { try (Stream<Path> s = Files.walk(tree, 3)) { List<String> l = s.map(FileStreams::rel).collect(Collectors.toList()); int sub = l.indexOf("tree/sub"), deep = l.indexOf("tree/sub/deep"), d = l.indexOf("tree/sub/deep/d.txt"), c = l.indexOf("tree/sub/c.txt");
            return (sub < deep) + " " + (deep < d) + " " + (sub < c) + " " + (Math.abs(d - deep) == 1 || d > deep); } });
        t("walk deleted while open", () -> { Path d = root.resolve("w3"); Files.createDirectories(d.resolve("x")); Files.createDirectories(d.resolve("z")); try (Stream<Path> s = Files.walk(d)) { return s.count(); } finally { rm(d); } });

        // Files.find
        BiPredicate<Path, BasicFileAttributes> txt = (p, a) -> a.isRegularFile() && p.toString().endsWith(".txt");
        t("find", () -> rels(Files.find(tree, 10, txt)));
        t("find depth 1", () -> rels(Files.find(tree, 1, txt)));
        t("find depth 2", () -> rels(Files.find(tree, 2, txt)));
        t("find depth 0", () -> rels(Files.find(tree, 0, (p, a) -> true)));
        t("find depth 0 start", () -> rels(Files.find(tree, 0, (p, a) -> a.isDirectory())));
        t("find depth negative", () -> Files.find(tree, -1, txt));
        t("find missing", () -> Files.find(root.resolve("nope"), 3, txt));
        t("find null path", () -> Files.find(null, 3, txt));
        t("find null matcher", () -> Files.find(tree, 3, null).count());
        t("find null option", () -> Files.find(tree, 3, txt, (FileVisitOption) null));
        t("find directories", () -> rels(Files.find(tree, 10, (p, a) -> a.isDirectory())));
        t("find by size", () -> rels(Files.find(tree, 10, (p, a) -> a.isRegularFile() && a.size() >= 3)));
        t("find none", () -> Files.find(tree, 10, (p, a) -> false).count());
        t("find attributes", () -> { try (Stream<Path> s = Files.find(tree, 10, (p, a) -> a.isRegularFile() && a.size() == 2)) { return s.map(FileStreams::rel).collect(Collectors.toList()); } });
        t("find predicate sees full paths", () -> { try (Stream<Path> s = Files.find(tree, 1, (p, a) -> p.startsWith(tree) && !p.equals(tree))) { return s.count(); } });
        t("find onClose", () -> { StringBuilder sb = new StringBuilder(); try (Stream<Path> s = Files.find(tree, 3, txt).onClose(() -> sb.append("closed"))) { s.count(); } return sb; });
        t("find file start", () -> rels(Files.find(abc, 3, (p, a) -> true)));
        t("find predicate throws", () -> Files.find(tree, 1, (p, a) -> { throw new IllegalStateException("pred"); }).count());

        // BufferedReader.lines
        t("br lines", () -> br("a\nb\r\nc\rd\n\ne").lines().collect(Collectors.toList()));
        t("br lines trailing", () -> br("x\ny\n").lines().collect(Collectors.toList()));
        t("br lines empty", () -> br("").lines().count());
        t("br lines only newline", () -> br("\n").lines().collect(Collectors.toList()).size());
        t("br lines blank lines", () -> br("\n\n\n").lines().count());
        t("br lines crlf pair", () -> br("a\r\n\r\nb").lines().collect(Collectors.toList()));
        t("br lines is lazy", () -> { StringBuilder sb = new StringBuilder(); BufferedReader r = new BufferedReader(new StringReader("a\nb\nc") { public int read(char[] c, int o, int l) { sb.append("read "); try { return super.read(c, o, l); } catch (IOException e) { throw new RuntimeException(e); } } });
            Stream<String> s = r.lines(); sb.append("made "); s.findFirst(); return sb.toString().trim(); });
        t("br lines findFirst then readLine", () -> { BufferedReader r = br("a\nb\nc"); String first = r.lines().findFirst().get(); return first + r.readLine(); });
        t("br lines limit then readLine", () -> { BufferedReader r = br("a\nb\nc\nd"); List<String> l = r.lines().limit(2).collect(Collectors.toList()); return l + r.readLine(); });
        t("br lines after readLine", () -> { BufferedReader r = br("a\nb\nc"); r.readLine(); return r.lines().collect(Collectors.toList()); });
        t("br lines twice", () -> { BufferedReader r = br("a\nb\nc"); Stream<String> s1 = r.lines(), s2 = r.lines(); return s1.limit(1).collect(Collectors.toList()) + "" + s2.limit(1).collect(Collectors.toList()); });
        t("br lines again after exhaustion", () -> { BufferedReader r = br("a\nb"); r.lines().count(); return r.lines().count(); });
        t("br lines iterator", () -> { Iterator<String> it = br("a\nb").lines().iterator(); return it.next() + it.next() + it.hasNext(); });
        t("br lines stream close keeps reader", () -> { BufferedReader r = br("a\nb\nc"); try (Stream<String> s = r.lines()) { s.findFirst(); } return r.readLine(); });
        t("br lines onClose", () -> { StringBuilder sb = new StringBuilder(); try (Stream<String> s = br("a\nb").lines().onClose(() -> sb.append("closed"))) { s.count(); } return sb; });
        t("br lines closed reader at creation", () -> { BufferedReader r = br("a"); r.close(); Stream<String> s = r.lines(); return "made"; });
        t("br lines closed reader", () -> { BufferedReader r = br("a"); r.close(); return r.lines().count(); });
        t("br lines closed midway", () -> { BufferedReader r = br("a\nb\nc"); Iterator<String> it = r.lines().iterator(); it.next(); r.close(); return it.hasNext(); });
        t("br lines read error", () -> new BufferedReader(new Reader() { public int read(char[] c, int o, int l) throws IOException { throw new IOException("boom"); } public void close() { } }).lines().count());
        t("br lines read error message", () -> { try { new BufferedReader(new Reader() { public int read(char[] c, int o, int l) throws IOException { throw new IOException("boom"); } public void close() { } }).lines().count(); return "no"; } catch (UncheckedIOException e) { return e.getCause().getMessage(); } });
        t("br lines ordered", () -> (br("a").lines().spliterator().characteristics() & Spliterator.ORDERED) != 0);
        t("br lines sized", () -> br("a\nb").lines().spliterator().hasCharacteristics(Spliterator.SIZED));
        t("br lines unknown size", () -> br("a\nb").lines().spliterator().estimateSize());
        t("br lines map filter", () -> br("1\n2\n3\n4").lines().mapToInt(Integer::parseInt).filter(x -> x % 2 == 0).sum());
        t("br lines parallel", () -> br("1\n2\n3\n4").lines().parallel().map(Integer::parseInt).reduce(0, Integer::sum));
        t("br lines with mark", () -> { BufferedReader r = br("a\nb\nc"); r.mark(100); r.lines().count(); r.reset(); return r.readLine(); });
        t("br lines on file reader", () -> { try (BufferedReader r = Files.newBufferedReader(abc)) { return r.lines().count(); } });
        t("br lines on file reader first", () -> { try (BufferedReader r = Files.newBufferedReader(abc, StandardCharsets.UTF_8)) { return r.lines().findFirst(); } });
        t("br lines on InputStreamReader", () -> new BufferedReader(new InputStreamReader(new ByteArrayInputStream("h\ni".getBytes("UTF-8")), "UTF-8")).lines().collect(Collectors.joining("/")));
    }
}
