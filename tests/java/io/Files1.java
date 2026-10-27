package io;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.Callable;

/** java.io.File on both platforms: the path rules that agree once the separator is shown as '/',
 *  and file operations in a fresh temporary directory, printed relative to it. */
public class Files1 {
    static File root;

    static String rel(Object o) {
        if (o == null) return "null";
        String s = o.toString();
        String r = root == null ? null : root.getPath();
        if (r != null && s.startsWith(r)) s = "<tmp>" + s.substring(r.length());
        return s.replace(File.separatorChar, '/');
    }

    static void t(String label, Callable<Object> c) {
        String r;
        try {
            r = rel(c.call());
        } catch (Throwable e) {
            r = "!" + e.getClass().getName() + (e instanceof IOException ? "" : e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        System.out.println(label + ": " + r);
    }

    static void path(String p) {
        File f = new File(p);
        String abs = f.getAbsolutePath();
        String ud = System.getProperty("user.dir");
        // user.dir may be a root ("/" inside LazyOS), which already ends with the separator.
        String base = ud.endsWith(File.separator) ? ud : ud + File.separator;
        String absRel = abs.equals(ud) ? "<cwd>" : abs.startsWith(base) ? "<cwd>/" + abs.substring(base.length()) : abs;
        System.out.println("[" + p + "] path=" + rel(f.getPath()) + " name=" + rel(f.getName()) + " parent=" + rel(f.getParent())
            + " abs=" + f.isAbsolute() + " absPath=" + rel(absRel) + " str=" + rel(f));
    }

    static void write(File f, int n) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            for (int i = 0; i < n; i++) o.write('a' + i % 26);
        }
    }

    public static void main(String[] args) throws Exception {
        for (String p : new String[]{"", ".", "..", "a", "a/", "a//b", "a/./b", "a/../b", "a/b/", "a/b//", "a/b/c.txt", "./a", "../a", ".x", "a.b.c"}) path(p);
        String sep = File.separator;
        t("File(a,b)", () -> new File("a", "b"));
        t("File(a/,b)", () -> new File("a" + sep, "b"));
        t("File(a,/b)", () -> new File("a", sep + "b"));
        t("File(a,b/)", () -> new File("a", "b" + sep));
        t("File('',b)", () -> new File("", "b"));
        t("File(null,b)", () -> new File((String) null, "b"));
        t("File(a,'')", () -> new File("a", ""));
        t("File(File a,b)", () -> new File(new File("a"), "b"));
        t("File(null File,b)", () -> new File((File) null, "b"));
        t("File(null)", () -> new File((String) null));
        t("File(a,null)", () -> new File("a", (String) null));
        t("equals", () -> new File("a/b").equals(new File("a" + sep + "b")) + " " + new File("a").equals(new File("a/")) + " " + new File("a").equals("a"));
        t("hashCode of x", () -> new File("x").hashCode() == ("x".hashCode() ^ 1234321));
        t("compareTo", () -> Integer.signum(new File("a").compareTo(new File("b"))) + " " + new File("a").compareTo(new File("a/")));
        t("separators", () -> File.separator.length() + " " + File.pathSeparator.length() + " " + (File.separatorChar == sep.charAt(0)));

        File tf = File.createTempFile("lazyio", null);
        t("createTempFile shape", () -> tf.getName().matches("lazyio-?\\d+\\.tmp") + " " + tf.isFile() + " " + tf.length() + " " + tf.getParentFile().equals(new File(System.getProperty("java.io.tmpdir")).getCanonicalFile()));
        root = File.createTempFile("lazyio", ".d");
        t("createTempFile suffix", () -> root.getName().matches("lazyio-?\\d+\\.d"));
        t("prefix too short", () -> File.createTempFile("ab", null));
        t("delete temp", () -> tf.delete() + " " + root.delete() + " " + root.mkdir() + " " + root.isDirectory());
        t("createTempFile in dir", () -> { File f = File.createTempFile("abc", ".x", root); return f.getParentFile().equals(root) + " " + f.getName().matches("abc-?\\d+\\.x") + " " + f.delete(); });
        t("createTempFile in missing dir", () -> File.createTempFile("abc", null, new File(root, "nope")));

        File missing = new File(root, "missing");
        t("missing", () -> missing.exists() + " " + missing.isFile() + " " + missing.isDirectory() + " " + missing.length() + " " + missing.lastModified()
            + " " + missing.canRead() + " " + missing.canWrite() + " " + missing.isHidden() + " " + missing.delete() + " " + Arrays.toString(missing.list()) + " " + missing.listFiles());
        t("missing renameTo", () -> missing.renameTo(new File(root, "other")));
        t("missing setLastModified", () -> missing.setLastModified(1000));
        t("missing canonical", () -> missing.getCanonicalPath().equals(root.getCanonicalPath() + sep + "missing"));
        File d = new File(root, "d");
        t("mkdir", () -> d.mkdir() + " " + d.mkdir() + " " + d.isDirectory() + " " + d.exists() + " " + d.isFile());
        t("mkdir missing parent", () -> new File(root, "x/y").mkdir());
        t("mkdirs", () -> new File(root, "x/y/z").mkdirs() + " " + new File(root, "x/y/z").mkdirs() + " " + new File(root, "x/y").isDirectory());
        t("mkdirs trailing sep", () -> new File(root, "m/n" + sep).mkdirs() + " " + new File(root, "m/n").isDirectory());
        File f = new File(d, "f.txt");
        t("createNewFile", () -> f.createNewFile() + " " + f.createNewFile() + " " + f.isFile() + " " + f.length());
        t("createNewFile in missing dir", () -> new File(root, "nope/f").createNewFile());
        t("createNewFile on dir", () -> d.createNewFile());
        t("mkdir on file", () -> f.mkdir() + " " + new File(f, "sub").mkdirs());
        t("length", () -> { write(f, 300); return f.length(); });
        t("setLastModified", () -> f.setLastModified(1234567890000L) + " " + f.lastModified());
        t("setLastModified -1", () -> f.setLastModified(-1));
        t("lastModified recent", () -> { File g = new File(root, "g"); write(g, 1); long m = g.lastModified(); boolean ok = Math.abs(m - System.currentTimeMillis()) < 600000; g.delete(); return ok; });
        t("delete non-empty dir", () -> d.delete() + " " + d.exists());
        for (String n : new String[]{"b.txt", "a.txt", "c", "a1", "a"}) write(new File(d, n), 2);
        t("list", () -> Arrays.toString(d.list()));
        t("list filter", () -> Arrays.toString(d.list((dir, name) -> name.endsWith(".txt"))));
        t("listFiles", () -> { File[] fs = d.listFiles(File::isFile); StringBuilder sb = new StringBuilder(); for (File x : fs) sb.append(rel(x)).append(' '); return sb; });
        t("listFiles parent", () -> d.listFiles()[0].getParentFile().equals(d));
        t("list on file", () -> Arrays.toString(f.list()) + " " + f.listFiles());
        t("list empty dir", () -> new File(root, "x/y/z").list().length);
        t("renameTo", () -> new File(d, "c").renameTo(new File(d, "c2")) + " " + new File(d, "c").exists() + " " + new File(d, "c2").length());
        t("renameTo other dir", () -> new File(d, "c2").renameTo(new File(root, "c3")) + " " + new File(root, "c3").isFile());
        t("renameTo missing dir", () -> new File(root, "c3").renameTo(new File(root, "nope/c4")));
        t("rename dir", () -> new File(root, "m").renameTo(new File(root, "m2")) + " " + new File(root, "m2/n").isDirectory());
        t("canonical dots", () -> new File(root, "p/./r/../r").getCanonicalPath().equals(new File(root, "p/r").getCanonicalPath()));
        t("canonicalFile", () -> new File(d, "..").getCanonicalFile().equals(root.getCanonicalFile()));
        t("absoluteFile", () -> new File("rel").getAbsoluteFile().getParent().equals(System.getProperty("user.dir")));
        t("toURI dir", () -> { String u = d.toURI().toString(); return u.startsWith("file:/") + " " + u.endsWith("/d/"); });
        t("toURI file", () -> { String u = f.toURI().toString(); return u.endsWith("/d/f.txt"); });
        t("toURI space", () -> new File(root, "a b").toURI().toString().endsWith("/a%20b"));
        t("isFile/isDirectory", () -> f.isFile() + " " + f.isDirectory() + " " + d.isFile() + " " + d.isDirectory() + " " + f.canRead() + " " + f.canWrite());
        t("setReadOnly", () -> { File r = new File(root, "ro"); write(r, 1); return r.setReadOnly() + " " + r.setWritable(true) + " " + r.delete(); });
        System.out.println("cleanup: " + delete(root));
        System.out.println("done");
    }

    static boolean delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        return f.delete();
    }
}
