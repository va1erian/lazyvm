package nio;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.regex.PatternSyntaxException;

/** Glob character classes (nio-file.md P4): which globs are refused, with which error and index,
 *  and which names the others match. Names and patterns avoid letter case, which only Windows
 *  ignores, so one golden serves both platforms (PathsWin pins the case rows). */
public class GlobClasses {
    static final String[] NAMES = {"-x", "_x", "]x", "ax", "9x", "0x", "!x", "^x", "&x", "x", "😀", "😀x",
        "😁x", "𐐀x", "[x"};

    static final String[] GLOBS = {
        // a '-' first, or first after '!', is a member
        "[!-a]x", "[!-9]x", "[-a]x", "[-]x", "[!-]x", "[a-]x", "[-a-]x", "[0-9-]x",
        // supplementary chars are one char of a name
        "[😀]", "[!😀]x", "[😀]x", "[!😀-😁]x", "[a😀]x",
        // a '-' after a range, or after a first '-', and backward ranges are refused
        "[a-c-]x", "[x-z-]", "[a-z-a]x", "[A-Z-a&]", "[--]x", "[---]x", "[--a]x", "[!a-c-e]x", "[c-a]x", "[!9-0]x",
        // the end of a range goes to the regular expression as it is
        "[S-[]x", "[0-[]x", "[!-[]x",
        // members the regular expression reads specially
        "[&&]x", "[&]x", "[^]x", "[^-a]x", "[!^-a]x", "[a^-c]x", "[[]x", "[[-a]x", "[!!]x", "[a!]x",
        // empty and unclosed classes
        "[]", "[]x", "[!]x", "[]]x", "[]-]x", "[", "[a", "[a-", "[a-c", "[!", "[-", "[--", "[c-", "x[a-c-",
        // a separator in a class
        "[a/b]x", "[!/]x", "[a-/]x",
    };

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= 0x20 && c < 0x7f ? String.valueOf(c) : String.format("U+%04x", (int) c));
        return b.toString();
    }

    public static void main(String[] args) {
        for (String g : GLOBS) {
            StringBuilder out = new StringBuilder(esc(g)).append(" ->");
            try {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
                for (String n : NAMES) {
                    if (m.matches(Paths.get(n))) {
                        out.append(' ').append(esc(n));
                    }
                }
            } catch (PatternSyntaxException e) {
                // The pattern of an error the regular expression finds is the platform's translation.
                boolean own = e.getPattern().equals(g);
                out.append(" PSE ").append(e.getDescription()).append(own ? " at " + e.getIndex() : " (regex)");
            }
            System.out.println(out);
        }
        System.out.println("done");
    }
}
