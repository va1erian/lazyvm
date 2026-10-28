package nio;

import java.io.File;
import java.nio.file.*;

/** Windows-only Path rules (roots, drive letters, UNC, invalid characters, case-insensitive
 *  comparison and matching), checked against expectations observed on the oracle. Elsewhere the
 *  checks are skipped and the same lines are printed, so one golden serves both platforms. */
public class PathsWin {
    static int mismatches;

    /** '|' stands for a backslash. */
    static String b(String s) {
        return s == null ? null : s.replace('|', '\\');
    }

    static String u(Object o) {
        return o == null ? "null" : o.toString().replace('\\', '|');
    }

    static void check(String what, Object actual, String expected) {
        String a = u(actual);
        if (!a.equals(expected)) {
            mismatches++;
            System.out.println("mismatch " + what + ": got " + a + " want " + expected);
        }
    }

    // input, toString, root, fileName, parent, nameCount, isAbsolute
    static final String[][] PATHS = {
        {"/", "|", "|", "null", "null", "0", "false"}, {"/a", "|a", "|", "a", "|", "1", "false"}, {"|a|b", "|a|b", "|", "b", "|a", "2", "false"},
        {"||s|h", "||s|h|", "||s|h|", "null", "null", "0", "true"}, {"||s|h|x", "||s|h|x", "||s|h|", "x", "||s|h|", "1", "true"},
        {"||.|x", "||.|x|", "||.|x|", "null", "null", "0", "true"},
        {"C:", "C:", "C:", "null", "null", "0", "false"}, {"C:|", "C:|", "C:|", "null", "null", "0", "true"}, {"C:/", "C:|", "C:|", "null", "null", "0", "true"},
        {"C:a", "C:a", "C:", "a", "C:", "1", "false"}, {"C:|a|", "C:|a", "C:|", "a", "C:|", "1", "true"}, {"c:/a//b/", "c:|a|b", "c:|", "b", "c:|a", "2", "true"},
        {"C:||a", "C:|a", "C:|", "a", "C:|", "1", "true"}, {"a:b", "a:b", "a:", "b", "a:", "1", "false"}, {"a:", "a:", "a:", "null", "null", "0", "false"},
        {"a.", "a.", "null", "a.", "null", "1", "false"}, {"a/ b", "a| b", "null", " b", "a", "2", "false"},
    };

    // input, InvalidPathException message
    static final String[][] INVALID = {
        {"//", "UNC path is missing hostname: //"}, {"||", "UNC path is missing hostname: ||"}, {"|||a", "UNC path is missing sharename: |||a"}, {"1:", "Illegal char <:> at index 1: 1:"},
        {"a ", "Trailing char < > at index 1: a "}, {"a*b", "Illegal char <*> at index 1: a*b"}, {"a?b", "Illegal char <?> at index 1: a?b"},
        {"a<b", "Illegal char <<> at index 1: a<b"}, {"a\"b", "Illegal char <\"> at index 1: a\"b"}, {"C:|a:b", "Illegal char <:> at index 4: C:|a:b"},
        {"||?|C:|x", "Illegal character [?] in path at index 2: ||?|C:|x"}, {"a\u0000b", "Illegal char <\u0000> at index 1: a\u0000b"},
    };

    // base, other, base.resolve(other)
    static final String[][] RESOLVE = {
        {"C:|a", "b", "C:|a|b"}, {"C:|a", "|b", "C:|b"}, {"C:|a", "D:b", "D:b"}, {"C:|a", "D:|b", "D:|b"}, {"C:|a", "C:b", "C:|a|b"},
        {"|a", "b", "|a|b"}, {"a", "C:|b", "C:|b"}, {"a", "|b", "|b"}, {"||s|h", "x", "||s|h|x"}, {"C:", "x", "C:x"},
        {"c:|b", "C:", "c:|b|"}, {"c:", "C:x", "C:x"}, {"c:a", "c:b", "c:b"},
    };

    // base, other, base.relativize(other) or the exception message
    static final String[][] RELATIVIZE = {
        {"C:|a|b", "C:|a|c", "..|c"}, {"C:|a", "c:|A|b", "b"}, {"|a", "|b", "..|b"},
        {"C:|a", "D:|a", "!'other' has different root"}, {"C:|a", "|a", "!'other' is different type of Path"}, {"a", "C:|a", "!'other' is different type of Path"},
        {"D:|", "||B|x", "!'other' is different type of Path"},
    };

    public static void main(String[] args) {
        if (File.separatorChar == '\\') {
            for (String[] p : PATHS) {
                Path x = Paths.get(b(p[0]));
                check("toString " + p[0], x, p[1]);
                check("root " + p[0], x.getRoot(), p[2]);
                check("fileName " + p[0], x.getFileName(), p[3]);
                check("parent " + p[0], x.getParent(), p[4]);
                check("nameCount " + p[0], x.getNameCount(), p[5]);
                check("isAbsolute " + p[0], x.isAbsolute(), p[6]);
            }
            for (String[] p : INVALID) {
                try {
                    check("invalid " + p[0], Paths.get(b(p[0])), "InvalidPathException");
                } catch (InvalidPathException e) {
                    check("invalid " + p[0], e.getMessage(), p[1]);
                    check("input " + p[0], e.getInput(), p[0]);
                }
            }
            for (String[] p : RESOLVE) check("resolve " + p[0] + " " + p[1], Paths.get(b(p[0])).resolve(b(p[1])), p[2]);
            for (String[] p : RELATIVIZE) {
                String got;
                try {
                    got = u(Paths.get(b(p[0])).relativize(Paths.get(b(p[1]))));
                } catch (IllegalArgumentException e) {
                    got = "!" + e.getMessage();
                }
                check("relativize " + p[0] + " " + p[1], got, p[2]);
            }
            check("normalize C:|..|a", Paths.get(b("C:|..|a")).normalize(), "C:|a");
            check("normalize |..|a", Paths.get(b("|..|a")).normalize(), "|a");
            check("normalize C:..|a", Paths.get(b("C:..|a")).normalize(), "C:..|a");
            check("equals ignores case", Paths.get("A").equals(Paths.get("a")), "true");
            check("compareTo ignores case", Paths.get("A").compareTo(Paths.get("a")), "0");
            check("hashCode ignores case", Paths.get("A").hashCode() == Paths.get("a").hashCode(), "true");
            check("startsWith ignores case", Paths.get(b("C:|A|b")).startsWith(b("c:|a")), "true");
            check("endsWith ignores case", Paths.get("a/B").endsWith("b"), "true");
            check("glob ignores case", FileSystems.getDefault().getPathMatcher("glob:*.TXT").matches(Paths.get("a.txt")), "true");
            check("regex ignores case", FileSystems.getDefault().getPathMatcher("regex:A").matches(Paths.get("a")), "true");
            check("toUri", Paths.get(b("C:|x y|z")).toUri(), "file:///C:/x%20y/z");
            check("toUri UNC", Paths.get(b("||s|h|x")).toUri(), "file://s/h/x");
            String drive = System.getProperty("user.dir").substring(0, 2);
            check("toAbsolutePath |a", Paths.get("/a").toAbsolutePath(), u(drive) + "|a");
            String userDir = u(System.getProperty("user.dir"));
            char letter = drive.charAt(0);
            String other = String.valueOf(Character.isUpperCase(letter) ? Character.toLowerCase(letter) : Character.toUpperCase(letter));
            check("toAbsolutePath other-case drive", Paths.get(other + ":").toAbsolutePath(), userDir + "|");
            check("toAbsolutePath other-case drive x", Paths.get(other + ":x").toAbsolutePath(), userDir + "|x");
            check("File other-case drive x", new File(other + ":x").getAbsolutePath(), other + userDir.substring(1) + "|x");
            check("File.toPath of a*b", tryToPath("a*b"), "Illegal char <*> at index 1: a*b");
            check("separator", FileSystems.getDefault().getSeparator(), "|");
            // Matching ignores case beyond ASCII too, for supplementary chars as well.
            PathMatcher e = FileSystems.getDefault().getPathMatcher("glob:é");
            check("glob e-acute vs E-acute", e.matches(Paths.get("É")), "true");
            PathMatcher deseret = FileSystems.getDefault().getPathMatcher("glob:[!𐐀]x");
            check("glob [!Deseret] vs its lower case", deseret.matches(Paths.get("𐐨x")), "false");
            PathMatcher upper = FileSystems.getDefault().getPathMatcher("glob:[𐐀]");
            check("glob [Deseret] vs its lower case", upper.matches(Paths.get("𐐨")), "true");
            check("regex ignores case", FileSystems.getDefault().getPathMatcher("regex:éX").matches(Paths.get("Éx")), "true");
        }
        System.out.println("windows path rules: " + (mismatches == 0 ? "no mismatch" : mismatches + " mismatches"));
        System.out.println("done");
    }

    static String tryToPath(String s) {
        try {
            return new File(s).toPath().toString();
        } catch (InvalidPathException e) {
            return e.getMessage();
        }
    }
}
