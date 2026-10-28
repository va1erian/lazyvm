package io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.io.IOException;

/** Windows-only java.io.File path rules (drive letters, UNC, backslashes, case-insensitive
 *  comparison), checked against expectations observed on the oracle. On other platforms the
 *  checks are skipped; the test prints the same lines either way so one golden serves both. */
public class FilePathsWin {
    static int mismatches;

    /** '|' stands for a backslash, so the table reads the same on any platform. */
    static String b(String s) {
        return s == null ? null : s.replace('|', '\\');
    }

    static void check(String what, Object actual, String expected) {
        String a = String.valueOf(actual);
        if (!a.equals(b(expected))) {
            mismatches++;
            System.out.println("mismatch " + what + ": got " + a.replace('\\', '|') + " want " + expected);
        }
    }

    // input, getPath, getName, getParent, isAbsolute
    static final String[][] PATHS = {
        {"", "", "", "null", "false"}, {"a/", "a", "a", "null", "false"}, {"a|", "a", "a", "null", "false"},
        {"a//b", "a|b", "b", "a", "false"}, {"a||b", "a|b", "b", "a", "false"}, {"a/|b", "a|b", "b", "a", "false"},
        {"a/./b", "a|.|b", "b", "a|.", "false"}, {"/", "|", "", "null", "false"}, {"|", "|", "", "null", "false"},
        {"/a", "|a", "a", "|", "false"}, {"|a|b", "|a|b", "b", "|a", "false"}, {"|..", "|..", "..", "|", "false"},
        {"//", "||", "", "null", "true"}, {"||", "||", "", "null", "true"}, {"///a", "||a", "a", "||", "true"}, {"|||a", "||a", "a", "||", "true"},
        {"||server|share", "||server|share", "share", "||server", "true"}, {"||server|share|", "||server|share", "share", "||server", "true"},
        {"//server/share/x", "||server|share|x", "x", "||server|share", "true"},
        {"C:", "C:", "", "null", "false"}, {"c:", "c:", "", "null", "false"}, {"C:|", "C:|", "", "null", "true"}, {"C:/", "C:|", "", "null", "true"},
        {"C:a", "C:a", "a", "C:", "false"}, {"C:..|x", "C:..|x", "x", "C:..", "false"}, {"C:|a|", "C:|a", "a", "C:|", "true"},
        {"c:/a//b/", "c:|a|b", "b", "c:|a", "true"}, {"C:||a", "C:|a", "a", "C:|", "true"}, {"C:|..", "C:|..", "..", "C:|", "true"},
        {"a:b", "a:b", "b", "a:", "false"}, {"1:", "1:", "1:", "null", "false"}, {"C:|a:b", "C:|a:b", "a:b", "C:|", "true"},
        {"||?|C:|x", "||?|C:|x", "x", "||?|C:", "true"}, {"||.|x", "||.|x", "x", "||.", "true"},
        {"a ", "a ", "a ", "null", "false"}, {"a.", "a.", "a.", "null", "false"},
        {"|C:b", "C:b", "b", "C:", "false"}, {"//c:", "c:", "", "null", "false"}, {" :/", " :|", "", " :", "false"}, {"/:/", "|:|", "", "|:", "false"},
    };

    // parent, child, getPath of new File(parent, child)
    static final String[][] PAIRS = {
        {"", "b", "|b"}, {"", "/b", "|b"}, {"", "C:b", "|C:b"}, {"", "", "|"}, {"", ".", "|."}, {"C:", "b", "C:|b"}, {"C:|", "b", "C:|b"},
        {"C:", "", "C:"}, {"|", "", "|"}, {"/", "b", "|b"}, {"a", "C:|b", "a|C:|b"}, {"||s|h", "b", "||s|h|b"}, {"a", "||b", "a|b"},
        {"a|", "|b", "a|b"}, {"a", ".", "a|."}, {"a", "..", "a|.."},
        {"a", "|", "a|"}, {"a", "||", "a"}, {"|", "||", ""}, {"||", "||", "|"}, {"C:|", "|", "C:|"}, {"a", "C:/", "a|C:|"},
    };

    /** The message setLength fails with, or "ok". */
    static String setLength(RandomAccessFile r, long len) {
        try {
            r.setLength(len);
            return "ok";
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    /** NUL is a device: it opens, takes writes, has length 0, and its pointer moves only by seek and setLength. */
    static void devices() throws IOException {
        try (FileOutputStream o = new FileOutputStream("NUL")) {
            o.write(new byte[10]);
        }
        try (RandomAccessFile r = new RandomAccessFile("NUL", "rw")) {
            r.write(1);
            check("NUL length and pointer", r.length() + " " + r.getFilePointer(), "0 0");
            r.seek(10);
            check("NUL pointer after seek", r.getFilePointer(), "10");
            check("NUL setLength(3)", setLength(r, 3) + " " + r.getFilePointer(), "Incorrect function 3");
            check("NUL skipBytes(4)", r.skipBytes(4) + " " + r.getFilePointer(), "-3 0");
        }
        File ro = File.createTempFile("lazyio", ".ro");
        try (RandomAccessFile r = new RandomAccessFile(ro, "r")) {
            r.seek(10);
            check("read-only setLength(20)", setLength(r, 20) + " " + r.getFilePointer(), "Access is denied 20");
        }
        check("cleanup read-only", ro.delete(), "true");
        try (FileInputStream in = new FileInputStream("NUL")) {
            check("NUL read", in.read(), "-1");
            try {
                check("NUL available", in.available(), "an IOException");
            } catch (IOException e) {
                check("NUL available", e.getMessage(), "Incorrect function");
            }
        }
        check("NUL readAllBytes", java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("NUL")).length, "0");
        File dir = java.nio.file.Files.createTempDirectory("lazyio").toFile();
        check("dir setWritable(false)", dir.setWritable(false), "false");
        check("dir setWritable(true)", dir.setWritable(true), "false");
        check("dir setReadOnly", dir.setReadOnly(), "false");
        check("dir still writable", dir.canWrite(), "true");
        File missing = new File(dir, "missing");
        check("missing setReadable(true)", missing.setReadable(true), "true");
        check("missing setExecutable(false)", missing.setExecutable(false), "false");
        check("missing setWritable(true)", missing.setWritable(true), "false");
        check("cleanup dir", dir.delete(), "true");
    }

    /** UNC host and share keep the case they are given; `\\?\UNC\` is not shown. */
    static void unc() throws IOException {
        if (!new File(b("||localhost|C$")).exists()) {
            // The administrative share is not reachable here: nothing to check.
            return;
        }
        String p = b("||LOCALHOST|c$|windows|..|Windows|System32");
        check("unc canonical", new File(p).getCanonicalPath(), "||LOCALHOST|c$|Windows|System32");
        check("unc real", java.nio.file.Paths.get(p).toRealPath(), "||LOCALHOST|c$|Windows|System32");
        check("unc real root", java.nio.file.Paths.get(b("||localhost|C$")).toRealPath(), "||localhost|C$|");
        check("unc canonical missing", new File(b("||localhost|C$|nope|x")).getCanonicalPath(), "||localhost|C$|nope|x");
        check("unc uri", new File(b("||localhost|C$|Windows")).toURI(), "file:////localhost/C$/Windows/");
    }

    public static void main(String[] args) throws IOException {
        boolean windows = File.separatorChar == '\\';
        if (windows) {
            for (String[] p : PATHS) {
                File f = new File(b(p[0]));
                check("path of " + p[0], f.getPath(), p[1]);
                check("name of " + p[0], f.getName(), p[2]);
                check("parent of " + p[0], f.getParent(), p[3]);
                check("absolute of " + p[0], f.isAbsolute(), p[4]);
            }
            for (String[] p : PAIRS) check("File(" + p[0] + "," + p[1] + ")", new File(b(p[0]), b(p[1])).getPath(), p[2]);
            String cwd = System.getProperty("user.dir");
            String drive = cwd.substring(0, 2);
            check("absPath of /a", new File("/a").getAbsolutePath(), drive + "|a");
            check("absPath of |", new File("\\").getAbsolutePath(), drive + "|");
            check("absPath of ''", new File("").getAbsolutePath().equals(cwd), "true");
            check("absPath of UNC", new File("\\\\s\\h\\x").getAbsolutePath(), "||s|h|x");
            check("canon strips trailing dot", new File("q.").getCanonicalPath().equals(cwd + "\\q"), "true");
            check("canon strips trailing space", new File("q ").getCanonicalPath().equals(cwd + "\\q"), "true");
            check("canon /", new File("/").getCanonicalPath(), drive + "|");
            check("canon C:|a|..|..|..", new File("C:\\a\\..\\..\\..").getCanonicalPath(), "C:|");
            check("canon drive case", new File("c:\\").getCanonicalPath(), "C:|");
            try {
                new File("\\\\").getCanonicalPath();
                check("canon of ||", "no exception", "IOException");
            } catch (IOException e) {
                check("canon of ||", "IOException", "IOException");
            }
            check("equals ignores case", new File("ABC").equals(new File("abc")), "true");
            check("compareTo ignores case", new File("ABC").compareTo(new File("abd")) < 0, "true");
            check("hashCode is of lower case", new File("ABC").hashCode() == ("abc".hashCode() ^ 1234321), "true");
            check("toURI", new File("C:\\x y\\z").toURI(), "file:/C:/x%20y/z");
            check("separators", File.separator + File.pathSeparator, "|;");
            devices();
            unc();
            File tmp = File.createTempFile("lazyio", ".d");
            File mixed = new File(tmp.getParentFile(), "LazyIO-Mixed" + System.nanoTime() + ".TXT");
            mixed.createNewFile();
            File lower = new File(mixed.getParentFile(), mixed.getName().toLowerCase());
            check("canon keeps on-disk case", lower.getCanonicalFile().getName().equals(mixed.getName()), "true");
            check("exists ignores case", lower.exists(), "true");
            check("cleanup", mixed.delete() && tmp.delete(), "true");
        }
        System.out.println("windows path rules: " + (mismatches == 0 ? "no mismatch" : mismatches + " mismatches"));
        System.out.println("done");
    }
}
