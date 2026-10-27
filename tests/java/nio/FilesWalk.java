package nio;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** DirectoryStream (all, glob, filter) and Files.walkFileTree (visit order, depth limits, the
 *  visitor results, failures) over a tree in a fresh temporary directory. Names are chosen so that
 *  directory order and name order agree. Files.walk/list/lines return streams (deferred to S11). */
public class FilesWalk {
    static Path root;

    static String rel(Path p) {
        String s = root.relativize(p).toString().replace(File.separatorChar, '/');
        return s.isEmpty() ? "." : s;
    }

    static String list(DirectoryStream<Path> ds) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> d = ds) {
            for (Path p : d) sb.append(rel(p)).append(' ');
        }
        return sb.toString().trim();
    }

    static String walk(Path start, int depth, final String skipSubtree, final String skipSiblings, final String terminate) throws IOException {
        final StringBuilder sb = new StringBuilder();
        Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), depth, new FileVisitor<Path>() {
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                sb.append("pre:").append(rel(d)).append(' ');
                return rel(d).equals(skipSubtree) ? FileVisitResult.SKIP_SUBTREE : rel(d).equals(terminate) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                sb.append(a.isDirectory() ? "dir:" : "file:").append(rel(f)).append(' ');
                if (rel(f).equals(skipSiblings)) return FileVisitResult.SKIP_SIBLINGS;
                return rel(f).equals(terminate) ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                sb.append("failed:").append(rel(f)).append('(').append(e.getClass().getSimpleName()).append(") ");
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path d, IOException e) {
                sb.append("post:").append(rel(d)).append(e == null ? "" : "(" + e.getClass().getSimpleName() + ")").append(' ');
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.toString().trim();
    }

    static void t(String label, java.util.concurrent.Callable<Object> c) {
        String r;
        try {
            r = String.valueOf(c.call());
        } catch (Throwable e) {
            String m = e.getMessage() == null ? "" : ": " + e.getMessage().replace(root.toString(), "<tmp>").replace(File.separatorChar, '/');
            r = "!" + e.getClass().getName() + m;
        }
        System.out.println(label + ": " + r);
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("lazywalk");
        for (String d : new String[]{"a", "a/b", "a/b/c", "a/e", "f"}) Files.createDirectory(root.resolve(d));
        for (String f : new String[]{"a/1.txt", "a/2.java", "a/b/3.txt", "a/b/c/4.txt", "a/z.txt", "f/5.java", "g.txt", "h"}) Files.write(root.resolve(f), new byte[]{1});

        t("stream all", () -> list(Files.newDirectoryStream(root)));
        t("stream a", () -> list(Files.newDirectoryStream(root.resolve("a"))));
        t("stream glob *.txt", () -> list(Files.newDirectoryStream(root.resolve("a"), "*.txt")));
        t("stream glob {*.java,b}", () -> list(Files.newDirectoryStream(root.resolve("a"), "{*.java,b}")));
        t("stream glob * includes dirs", () -> list(Files.newDirectoryStream(root, "*")));
        t("stream glob */x does not match names", () -> list(Files.newDirectoryStream(root, "a/*")));
        t("stream filter dirs", () -> list(Files.newDirectoryStream(root, Files::isDirectory)));
        t("stream empty dir", () -> "[" + list(Files.newDirectoryStream(root.resolve("a/e"))) + "]");
        t("stream entries resolve against dir", () -> { try (DirectoryStream<Path> d = Files.newDirectoryStream(root.resolve("f"))) { Path p = d.iterator().next(); return p.getParent().equals(root.resolve("f")) + " " + p.getFileName(); } });
        t("stream missing", () -> Files.newDirectoryStream(root.resolve("nope")));
        t("stream on file", () -> Files.newDirectoryStream(root.resolve("h")));
        t("stream bad glob", () -> { try { Files.newDirectoryStream(root, "{a"); return "?"; } catch (java.util.regex.PatternSyntaxException e) { return "PatternSyntaxException"; } });
        t("stream iterator twice", () -> { try (DirectoryStream<Path> d = Files.newDirectoryStream(root)) { d.iterator(); d.iterator(); return "?"; } });
        t("stream iterator after close", () -> { DirectoryStream<Path> d = Files.newDirectoryStream(root); d.close(); d.close(); d.iterator(); return "?"; });
        t("stream close during iteration", () -> { DirectoryStream<Path> d = Files.newDirectoryStream(root); Iterator<Path> it = d.iterator(); it.next(); d.close(); return it.hasNext(); });
        t("stream next at end", () -> { try (DirectoryStream<Path> d = Files.newDirectoryStream(root.resolve("a/e"))) { return d.iterator().next(); } });
        t("stream iterator remove", () -> { try (DirectoryStream<Path> d = Files.newDirectoryStream(root)) { Iterator<Path> it = d.iterator(); it.next(); it.remove(); return "?"; } });

        t("walk", () -> walk(root, Integer.MAX_VALUE, null, null, null));
        t("walk depth 0", () -> walk(root, 0, null, null, null));
        t("walk depth 1", () -> walk(root, 1, null, null, null));
        t("walk depth 2 from a", () -> walk(root.resolve("a"), 2, null, null, null));
        t("walk skip subtree a/b", () -> walk(root.resolve("a"), Integer.MAX_VALUE, "a/b", null, null));
        t("walk skip siblings after a/2.java", () -> walk(root.resolve("a"), Integer.MAX_VALUE, null, "a/2.java", null));
        t("walk terminate at a/b/3.txt", () -> walk(root, Integer.MAX_VALUE, null, null, "a/b/3.txt"));
        t("walk terminate at pre a/b", () -> walk(root, Integer.MAX_VALUE, null, null, "a/b"));
        t("walk a file", () -> walk(root.resolve("g.txt"), Integer.MAX_VALUE, null, null, null));
        t("walk missing", () -> walk(root.resolve("nope"), Integer.MAX_VALUE, null, null, null));
        t("walk negative depth", () -> walk(root, -1, null, null, null));
        t("walk returns start", () -> Files.walkFileTree(root.resolve("f"), new SimpleFileVisitor<Path>() { }).equals(root.resolve("f")));
        t("SimpleFileVisitor failure rethrows", () -> Files.walkFileTree(root.resolve("nope"), new SimpleFileVisitor<Path>() { }));
        t("FileVisitResult", () -> Arrays.toString(FileVisitResult.values()) + " " + Arrays.toString(FileVisitOption.values()));

        // Delete the tree bottom-up with a visitor, the usual idiom.
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
        System.out.println("cleanup: " + Files.notExists(root));
        System.out.println("done");
    }
}
