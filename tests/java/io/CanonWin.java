package io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;

/** Windows-only canonical and real paths (io.md F7, nio-file.md NF10) and the Windows file rules
 *  next to them, checked against expectations observed on the oracle. Elsewhere the checks are
 *  skipped and the same lines are printed, so one golden serves both platforms. */
public class CanonWin {
    static int mismatches;
    static String base;

    /** '|' stands for a backslash and <B> for the scratch directory. */
    static String u(String s) {
        return s.replace(base, "<B>").replace('\\', '|');
    }

    static void check(String what, Object actual, String expected) {
        String a = u(String.valueOf(actual));
        if (!a.equals(expected)) {
            mismatches++;
            System.out.println("mismatch " + what + ": got " + a + " want " + expected);
        }
    }

    static final String SYNTAX = "IOE The filename, directory name, or volume label syntax is incorrect";

    // relative to the scratch directory (d2 a directory, d2|in and f2 files), getCanonicalPath
    static final String[][] CANONICAL = {
        {"nope.|x", "<B>|nope|x"}, {"nope .|x", "<B>|nope |x"}, {"nope. |x", "<B>|nope. |x"}, {"nope .", "<B>|nope"},
        {"d2 |in", "<B>|d2|in"}, {"d2. |in", "<B>|d2|in"}, {". .|x", "<B>|. |x"}, {"x|. .|y", "<B>|x|. |y"},
        {"nope..|x", "IOE Bad pathname"}, {"d2..|in", "IOE Bad pathname"}, {" ...|x", "IOE Bad pathname"},
        {"nope..|..|x", "<B>|x"}, {"d2|in. .", "<B>|d2|in"}, {"... |x", "<B>|... |x"},
        {"f2:x", SYNTAX}, {"f2::$DATA", SYNTAX}, {"d2|a:b", SYNTAX}, {"d2::$INDEX_ALLOCATION|in", SYNTAX},
        {"nope:x|y", SYNTAX}, {"x|..|a:b", SYNTAX}, {"d2|a:", SYNTAX},
        {"f2|a:b", "<B>|f2|a:b"}, {"nope|a:b", "<B>|nope|a:b"}, {"d2|nope|a:b", "<B>|d2|nope|a:b"},
        {"nope|NUL", "||.|NUL"}, {"d2|NUL:", "||.|NUL"}, {"NUL.", "||.|NUL"}, {"x|NUL|y", "<B>|x|NUL|y"},
        {"NUL.|x", "<B>|NUL|x"}, {"nope..|NUL", "||.|NUL"}, {"d2..|NUL", "||.|NUL"}, {"d2|x..|NUL", "||.|NUL"},
        {"nope|nul:|in|..", "<B>|nope|nul:"}, {"d2|NUL|x|..", "<B>|d2|NUL"}, {"d2|NUL|.", "<B>|d2|NUL"},
        // e2 is used by these rows only: the oracle's canonical cache would remember an earlier "e2 ".
        {"e2 |a:b", "<B>|e2|a:b"}, {"e2. |in:x", "<B>|e2|in:x"}, {"e2 .|f:x", "<B>|e2|f:x"}, {"e2.|a:b", SYNTAX},
    };

    // relative to the scratch directory, toRealPath (or its exception)
    static final String[][] REAL = {
        {"nope.|x", "NoSuchFileException <B>|nope|x"}, {"nope..|x", "NoSuchFileException <B>|nope..|x"},
        {"nope .", "NoSuchFileException <B>|nope"}, {". .|x", "NoSuchFileException <B>|. |x"},
        {"nope|..|q|.|r", "NoSuchFileException <B>|q|r"}, {"d2..|in", "<B>|d2|in"}, {"d2.|in", "<B>|d2|in"},
        {"D2|IN", "<B>|d2|in"}, {"NUL", "<B>|NUL"}, {"d2|NUL", "<B>|d2|NUL"},
        {"nope|NUL", "NoSuchFileException <B>|nope|NUL"}, {"f2|x", "FileSystemException <B>|f2|x: The directory name is invalid.|r|n"},
    };

    static String canonical(String p) {
        try {
            return new File(p).getCanonicalPath();
        } catch (IOException e) {
            return "IOE " + e.getMessage();
        }
    }

    static String real(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + " " + e.getMessage().replace("\r", "|r").replace("\n", "|n");
        }
    }

    static String error(IOOp op) {
        try {
            op.run();
            return "ok";
        } catch (IOException e) {
            return e.getClass().getSimpleName();
        }
    }

    interface IOOp {
        void run() throws IOException;
    }

    static void files(final File root) throws IOException {
        final Path d2 = root.toPath().resolve("d2");
        final Path f2 = root.toPath().resolve("f2");
        check("CREATE_NEW onto a directory", error(() -> Files.newOutputStream(d2, StandardOpenOption.CREATE_NEW).close()),
                "AccessDeniedException");
        final Path empty = Files.createDirectory(root.toPath().resolve("empty"));
        check("ATOMIC_MOVE onto an empty directory", error(() -> Files.move(f2, empty, StandardCopyOption.ATOMIC_MOVE)),
                "AccessDeniedException");
        check("file kept", Files.isRegularFile(f2) + " " + Files.isDirectory(empty), "true true");
        final Path dir = Files.createDirectory(root.toPath().resolve("dir"));
        check("ATOMIC_MOVE a directory onto an empty one", error(() -> Files.move(dir, empty, StandardCopyOption.ATOMIC_MOVE)),
                "AccessDeniedException");
        Files.write(empty.resolve("x"), new byte[1]);
        check("ATOMIC_MOVE a directory onto a full one", error(() -> Files.move(dir, empty, StandardCopyOption.ATOMIC_MOVE)),
                "AccessDeniedException");
        check("directories kept", Files.isDirectory(dir) + " " + Files.exists(empty.resolve("x")), "true true");
        Files.delete(empty.resolve("x"));
        Files.delete(dir);
        Files.delete(empty);
        Files.setLastModifiedTime(f2, FileTime.fromMillis(1234567890000L));
        // Observed: the first instant of 1601 is Windows' "no change".
        Files.setLastModifiedTime(f2, FileTime.fromMillis(-11644473600000L));
        check("setLastModifiedTime(1601)", Files.getLastModifiedTime(f2).toMillis(), "1234567890000");
    }

    static String pad(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('a');
        return sb.toString();
    }

    /** NUL near the path length limit, and real directories named NUL (made through \\?\). */
    static void deviceNames(File root) throws IOException {
        check("canonical nope|NUL|", canonical(base + "\\nope\\NUL\\"), "||.|NUL");
        for (int len = 246; len <= 249 && base.length() + 6 < len; len++) {
            String dir = base + "\\" + pad(len - base.length() - 5);
            new File(dir).mkdir();
            // Observed: up to 247 units NUL in an existing directory is the device, then a name.
            check("real NUL at " + len, real(Paths.get(dir, "NUL")), len < 248 ? u(dir) + "|NUL" : "NoSuchFileException " + u(dir) + "|NUL");
            check("real nope|NUL at " + len, real(Paths.get(dir, "nope", "NUL")), "NoSuchFileException " + u(dir) + "|nope|NUL");
            new File(dir).delete();
        }
        String d2 = base + "\\d2";
        String verbatim = "\\\\?\\" + d2 + "\\NUL";
        check("make d2|NUL", new File(verbatim).mkdir(), "true");
        check("canonical \\\\.\\ d2|NUL", canonical("\\\\.\\" + d2 + "\\NUL"), "||.|" + u(d2) + "|NUL");
        String share = "\\\\localhost\\C$" + d2.substring(2);
        if (new File("\\\\localhost\\C$").exists()) {
            check("unc real of a real NUL", real(Paths.get(share, "NUL")), u(share) + "|NUL");
        }
        check("remove d2|NUL", new File(verbatim).delete(), "true");
        StringBuilder lb = new StringBuilder(base);
        for (int i = 0; i < 6; i++) lb.append("\\long_name_component_of_forty_characters").append(i);
        String deep = lb.toString();
        new File(deep).mkdirs();
        check("make long|NUL", new File("\\\\?\\" + deep + "\\NUL").mkdir(), "true");
        check("real of a long real NUL", real(Paths.get(deep, "NUL")), u(deep) + "|NUL");
        check("real of a long real NUL|.", real(Paths.get(deep, "NUL", ".")), u(deep) + "|NUL");
        check("real of a long missing NUL", real(Paths.get(deep, "nope", "NUL")), "NoSuchFileException " + u(deep) + "|nope|NUL");
        new File("\\\\?\\" + deep + "\\NUL").delete();
        for (File f = new File(deep); !f.equals(root); f = f.getParentFile()) {
            f.delete();
        }
    }

    public static void main(String[] args) throws IOException {
        if (File.separatorChar == '\\') {
            File root = Files.createTempDirectory("lazycanon").toFile().getCanonicalFile();
            base = root.getPath();
            File d2 = new File(root, "d2");
            d2.mkdir();
            File e2 = new File(root, "e2");
            e2.mkdir();
            new FileOutputStream(new File(root, "f2")).close();
            new FileOutputStream(new File(d2, "in")).close();
            for (String[] c : CANONICAL) {
                check("canonical " + c[0], canonical(base + "\\" + c[0].replace('|', '\\')), c[1]);
            }
            for (String[] c : REAL) {
                check("real " + c[0], real(Paths.get(base, c[0].replace('|', '\\'))), c[1]);
            }
            check("canonical drive root|a:b", canonical(base.substring(0, 3) + "a:b"), SYNTAX);
            if (new File("\\\\localhost\\C$").exists()) {
                check("unc a:b", canonical("\\\\localhost\\C$\\a:b"), SYNTAX);
                check("unc nope|a:b", canonical("\\\\localhost\\C$\\nope\\a:b"), "||localhost|C$|nope|a:b");
                check("unc nope..|x", canonical("\\\\localhost\\C$\\nope..\\x"), "IOE Bad pathname");
                // Observed: on a share NUL is an ordinary name, which the share refuses.
                String share = "\\\\localhost\\C$" + base.substring(2);
                check("unc nul:", canonical(share + "\\nul:"), SYNTAX);
                check("unc d2|NUL", canonical(share + "\\d2\\NUL"), u(share) + "|d2|NUL");
                check("unc real d2|NUL", real(Paths.get(share, "d2", "NUL")), "NoSuchFileException " + u(share) + "|d2|NUL");
                check("unc real CON", real(Paths.get(share, "CON")), "NoSuchFileException " + u(share) + "|CON");
                check("unc real COM1", real(Paths.get(share, "COM1")), "NoSuchFileException " + u(share) + "|COM1");
                check("unc real CON.txt", real(Paths.get(share, "CON.txt")), "NoSuchFileException " + u(share) + "|CON.txt");
            }
            deviceNames(root);
            files(root);
            new File(d2, "in").delete();
            d2.delete();
            e2.delete();
            new File(root, "f2").delete();
            check("cleanup", root.delete(), "true");
        }
        System.out.println("windows canonical rules: " + (mismatches == 0 ? "no mismatch" : mismatches + " mismatches"));
        System.out.println("done");
    }
}
