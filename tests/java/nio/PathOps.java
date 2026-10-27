package nio;

import java.io.File;
import java.nio.file.*;
import java.util.concurrent.Callable;

/** Path operations on relative paths, which behave the same on Windows and Unix once the separator
 *  is shown as '/': parsing, names, normalize, resolve, relativize, startsWith/endsWith, glob. */
public class PathOps {
    static void t(String label, Callable<Object> c) {
        String r;
        try {
            Object o = c.call();
            r = o == null ? "null" : o.toString().replace(File.separatorChar, '/');
        } catch (Throwable e) {
            r = "!" + e.getClass().getName();
        }
        System.out.println(label + ": " + r);
    }

    static Path p(String s) {
        return Paths.get(s);
    }

    static String names(Path x) {
        StringBuilder sb = new StringBuilder();
        for (Path n : x) sb.append('<').append(n).append('>');
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        for (String s : new String[]{"", "a", "a/", "a//b", "a/b/", "a/./b", "./a", "../a", "a/..", ".", "..", "a/b/c.txt", ".x"}) {
            Path x = p(s);
            System.out.println("[" + s + "] str=" + x.toString().replace(File.separatorChar, '/') + " n=" + x.getNameCount() + " names=" + names(x)
                + " file=" + x.getFileName() + " parent=" + (x.getParent() == null ? "null" : x.getParent().toString().replace(File.separatorChar, '/'))
                + " root=" + x.getRoot() + " abs=" + x.isAbsolute() + " norm=[" + x.normalize().toString().replace(File.separatorChar, '/') + "]");
        }
        for (String s : new String[]{"a/./b/../c", "a/../..", "../../a", "a/b/../../..", "./.", "a/./.", "../a/../b", "a/b/c/../../d"}) {
            t("normalize " + s, () -> "[" + p(s).normalize() + "] n=" + p(s).normalize().getNameCount());
        }
        t("getName(1)", () -> p("a/b/c").getName(1));
        t("getName(3)", () -> p("a/b/c").getName(3));
        t("getName(-1)", () -> p("a/b/c").getName(-1));
        t("getName(0) of empty", () -> "[" + p("").getName(0) + "]");
        t("subpath(1,3)", () -> p("a/b/c/d").subpath(1, 3));
        t("subpath(1,1)", () -> p("a/b/c").subpath(1, 1));
        t("subpath(0,4)", () -> p("a/b/c").subpath(0, 4));
        t("Paths.get(a,b,c)", () -> Paths.get("a", "b", "c"));
        t("Paths.get(a,'',b)", () -> Paths.get("a", "", "b"));
        t("Paths.get('',a)", () -> Paths.get("", "a"));
        t("Paths.get('','')", () -> "[" + Paths.get("", "") + "]");
        t("Paths.get(a/,/b)", () -> Paths.get("a/", "/b"));
        t("Paths.get(null)", () -> Paths.get((String) null));
        t("resolve", () -> p("a").resolve("b") + " " + p("a/b").resolve("c/d") + " [" + p("a").resolve("") + "] " + p("").resolve("a") + " [" + p("").resolve("") + "]");
        t("resolve absolute", () -> { Path abs = p("x").toAbsolutePath(); return p("a").resolve(abs).equals(abs); });
        t("resolveSibling", () -> p("a/b").resolveSibling("c") + " " + p("a").resolveSibling("c") + " [" + p("a/b").resolveSibling("") + "]");
        t("relativize", () -> p("a/b").relativize(p("a/c")) + " [" + p("a").relativize(p("a")) + "] " + p("a").relativize(p("a/b/c")) + " " + p("a/b").relativize(p("a")) + " " + p("a/b").relativize(p("c/d")));
        t("relativize empty", () -> "[" + p("").relativize(p("a")) + "] [" + p("a").relativize(p("")) + "]");
        t("relativize dots", () -> p("a/./b").relativize(p("a/c")) + " | " + p("a/../b").relativize(p("c")));
        t("relativize rel to abs", () -> p("a").relativize(p("a").toAbsolutePath()));
        t("startsWith", () -> p("a/b").startsWith("a") + " " + p("a/b").startsWith("a/") + " " + p("ab").startsWith("a") + " " + p("a/b").startsWith("a/b") + " " + p("a/b").startsWith("a/b/c") + " " + p("a").startsWith("") + " " + p("").startsWith(""));
        t("endsWith", () -> p("a/b").endsWith("b") + " " + p("a/b").endsWith("a/b") + " " + p("a/b").endsWith("/b") + " " + p("ab").endsWith("b") + " " + p("a/b").endsWith(""));
        t("startsWith absolute", () -> p("a").toAbsolutePath().startsWith(p("a")));
        t("equals", () -> p("a/b").equals(p("a//b/")) + " " + p("a/./b").equals(p("a/b")) + " " + p("a").equals("a") + " " + p("").equals(p("")));
        t("compareTo", () -> Integer.signum(p("a").compareTo(p("b"))) + " " + p("a/b").compareTo(p("a//b")) + " " + Integer.signum(p("a").compareTo(p("a/b"))));
        t("hashCode equal paths", () -> p("a/b").hashCode() == p("a//b").hashCode());
        t("toAbsolutePath", () -> p("a").toAbsolutePath().equals(Paths.get(System.getProperty("user.dir"), "a")) + " " + p("").toAbsolutePath().equals(p(System.getProperty("user.dir"))) + " " + p("a").toAbsolutePath().isAbsolute());
        t("toFile/toPath", () -> p("a/b").toFile().getPath().equals(new File("a/b").getPath()) + " " + new File("a/b/").toPath().equals(p("a/b")) + " [" + new File("").toPath() + "]");
        t("separator", () -> FileSystems.getDefault().getSeparator().equals(File.separator) + " " + (p("a").getFileSystem() == FileSystems.getDefault()));
        t("iterator remove", () -> { java.util.Iterator<Path> it = p("a/b").iterator(); it.next(); it.remove(); return "?"; });

        PathMatcher star = FileSystems.getDefault().getPathMatcher("glob:*.txt");
        t("glob *.txt", () -> star.matches(p("a.txt")) + " " + star.matches(p("d/a.txt")) + " " + star.matches(p(".txt")) + " " + star.matches(p("a.txt.x")));
        PathMatcher deep = FileSystems.getDefault().getPathMatcher("glob:**/*.java");
        t("glob **/*.java", () -> deep.matches(p("a/b/C.java")) + " " + deep.matches(p("C.java")) + " " + deep.matches(p("a/C.java")));
        PathMatcher alt = FileSystems.getDefault().getPathMatcher("glob:{a,bc}?[0-9x]");
        t("glob {a,bc}?[0-9x]", () -> alt.matches(p("az1")) + " " + alt.matches(p("bcqx")) + " " + alt.matches(p("a1")) + " " + alt.matches(p("bz9")) + " " + alt.matches(p("a/1")));
        PathMatcher neg = FileSystems.getDefault().getPathMatcher("glob:[!a-c]*");
        t("glob [!a-c]*", () -> neg.matches(p("d")) + " " + neg.matches(p("b")) + " " + neg.matches(p("")));
        PathMatcher one = FileSystems.getDefault().getPathMatcher("glob:a*");
        t("glob a* spans no separator", () -> one.matches(p("ab")) + " " + one.matches(p("a/b")));
        PathMatcher rx = FileSystems.getDefault().getPathMatcher("regex:a.c");
        t("regex", () -> rx.matches(p("abc")) + " " + rx.matches(p("abcd")));
        t("glob bad syntax", () -> FileSystems.getDefault().getPathMatcher("glob:{a"));
        t("glob nested braces", () -> FileSystems.getDefault().getPathMatcher("glob:{a,{b}}"));
        t("unknown syntax", () -> FileSystems.getDefault().getPathMatcher("foo:x"));
        t("no syntax", () -> FileSystems.getDefault().getPathMatcher("x"));
        t("syntax case", () -> FileSystems.getDefault().getPathMatcher("GLOB:*").matches(p("q")));
        System.out.println("done");
    }
}
