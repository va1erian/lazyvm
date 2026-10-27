package lazy.internal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.List;

/** Windows path rules: drives, UNC shares, `\` and `/` alike, names ignoring case (io.md W1, PW1). */
final class WindowsPaths extends PathSyntax {
    private static final String ILLEGAL = "<>:\"|?*";

    public char separator() {
        return '\\';
    }

    public boolean ignoresCase() {
        return true;
    }

    private static boolean slash(char c) {
        return c == '\\' || c == '/';
    }

    private static boolean driveLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean hasDrive(String s) {
        return s.length() >= 2 && s.charAt(1) == ':' && driveLetter(s.charAt(0));
    }

    // Spec W1: `/` becomes `\`; two or more leading separators are a UNC prefix `\\`.
    public String normalizeFile(String path) {
        String s = path.replace('/', '\\');
        int lead = 0;
        while (lead < s.length() && s.charAt(lead) == '\\') {
            lead++;
        }
        if (lead > 0 && hasDrive(s.substring(lead))) {
            // Observed: separators before a drive are dropped, so `\C:b` is `C:b`, `//c:` is `c:`.
            s = s.substring(lead);
            lead = 0;
        }
        if (lead >= 2) {
            return collapse("\\\\" + s.substring(lead), 2, '\\');
        }
        // Observed: any char before ":\" makes a root that keeps its separator (" :\" and "\:\"
        // stay), though only a letter makes a drive prefix.
        if (s.length() > 2 && s.charAt(1) == ':' && s.charAt(2) == '\\') {
            return collapse(s, 3, '\\');
        }
        return collapse(s, lead, '\\');
    }

    // Spec W1: `\\` (UNC) 2, `\` 1, `C:\` 3, `C:` 2.
    public int prefixLength(String path) {
        if (path.startsWith("\\\\")) {
            return 2;
        }
        if (path.startsWith("\\")) {
            return 1;
        }
        if (hasDrive(path)) {
            return path.length() > 2 && path.charAt(2) == '\\' ? 3 : 2;
        }
        return 0;
    }

    public boolean isAbsoluteFile(String path) {
        int p = prefixLength(path);
        return p == 3 || (p == 2 && path.startsWith("\\\\"));
    }

    /**
     * Observed (tests/java/io/FilePathsWin and a table of pairs on the oracle):
     * the parent loses a trailing `\`, the child a leading `\` or `\\`, and one
     * `\` joins them unless the child's rest already starts with one; a child
     * that was only `\\` leaves the shortened parent alone.
     */
    public String resolveFile(String parent, String child) {
        if (child.isEmpty()) {
            return parent;
        }
        int start = child.length() > 1 && child.charAt(0) == '\\' ? (child.charAt(1) == '\\' ? 2 : 1) : 0;
        String p = parent.endsWith("\\") ? parent.substring(0, parent.length() - 1) : parent;
        if (start >= child.length()) {
            return p;
        }
        return child.charAt(start) == '\\' ? p + child.substring(start) : p + "\\" + child.substring(start);
    }

    public String absoluteFile(String path, String userDir) {
        int p = prefixLength(path);
        if (isAbsoluteFile(path)) {
            return path;
        }
        if (p == 1) {
            return drive(userDir) + path;
        }
        if (p == 2) {
            String rest = path.substring(2);
            if (path.substring(0, 2).equalsIgnoreCase(drive(userDir))) {
                // Observed: the drive keeps the case it was given.
                String dir = path.substring(0, 2) + userDir.substring(2);
                return rest.isEmpty() ? dir : resolveFile(dir, rest);
            }
            // Observed: another drive's relative path joins its root with one more `\`
            // when the drive is there (its current directory), and with none when it is not.
            String root = path.substring(0, 2) + "\\";
            if (FileSystem.attributes(root) == 0) {
                return rest.isEmpty() ? path : root + rest;
            }
            return rest.isEmpty() ? root : root + "\\" + rest;
        }
        return path.isEmpty() ? userDir : resolveFile(userDir, path);
    }

    /** The drive (or UNC share) of the current directory. */
    private static String drive(String userDir) {
        if (hasDrive(userDir)) {
            return userDir.substring(0, 2);
        }
        Parsed p = new WindowsPaths().parsePath(userDir);
        return p.root == null ? "" : p.root.substring(0, p.root.length() - 1);
    }

    // Observed: a UNC path without a share has no canonical form.
    public String precanonical(String absolute) throws IOException {
        if (absolute.startsWith("\\\\") && split(absolute, 2, '\\').size() < 2) {
            throw new IOException("Invalid argument");
        }
        return absolute;
    }

    // Spec W1: the canonical drive letter is upper case.
    public String postcanonical(String canonical) {
        if (hasDrive(canonical)) {
            return Character.toUpperCase(canonical.charAt(0)) + canonical.substring(1);
        }
        return canonical;
    }

    private static InvalidPathException invalid(String input, String reason, int index) {
        return new InvalidPathException(input, reason, index);
    }

    // Spec PW1: UNC roots `\\host\share\`, drives, `\`; illegal and trailing chars refused.
    public Parsed parsePath(String input) {
        int n = input.length();
        String root = null;
        int i = 0;
        if (n >= 2 && slash(input.charAt(0)) && slash(input.charAt(1))) {
            i = 2;
            while (i < n && slash(input.charAt(i))) {
                i++;
            }
            int hostStart = i;
            i = uncPart(input, i);
            if (i == hostStart) {
                throw invalid(input, "UNC path is missing hostname", -1);
            }
            String host = input.substring(hostStart, i);
            while (i < n && slash(input.charAt(i))) {
                i++;
            }
            int shareStart = i;
            i = uncPart(input, i);
            if (i == shareStart) {
                throw invalid(input, "UNC path is missing sharename", -1);
            }
            root = "\\\\" + host + "\\" + input.substring(shareStart, i) + "\\";
        } else if (hasDrive(input)) {
            root = input.substring(0, 2);
            i = 2;
            if (i < n && slash(input.charAt(i))) {
                root += "\\";
            }
        } else if (n > 0 && slash(input.charAt(0))) {
            root = "\\";
        }
        List<String> names = names(input, i);
        if (root == null && names.isEmpty()) {
            names.add("");
        }
        return new Parsed(root, names.toArray(new String[0]));
    }

    /** The end of a UNC host or share name from `i`; its chars must be legal. */
    private static int uncPart(String input, int i) {
        while (i < input.length() && !slash(input.charAt(i))) {
            char c = input.charAt(i);
            if (c < ' ' || ILLEGAL.indexOf(c) >= 0) {
                throw invalid(input, "Illegal character [" + c + "] in path", i);
            }
            i++;
        }
        return i;
    }

    private static List<String> names(String input, int from) {
        List<String> names = new java.util.ArrayList<>();
        int i = from;
        int n = input.length();
        while (i < n) {
            while (i < n && slash(input.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !slash(input.charAt(i))) {
                char c = input.charAt(i);
                if (c < ' ' || ILLEGAL.indexOf(c) >= 0) {
                    throw invalid(input, "Illegal char <" + c + ">", i);
                }
                i++;
            }
            if (i > start) {
                if (input.charAt(i - 1) == ' ') {
                    throw invalid(input, "Trailing char < >", i - 1);
                }
                names.add(input.substring(start, i));
            }
        }
        return names;
    }

    public int rootKind(String root) {
        if (root == null) {
            return 0;
        }
        if (root.equals("\\")) {
            return 2;
        }
        if (root.startsWith("\\\\")) {
            return 4;
        }
        return root.endsWith("\\") ? 3 : 1;
    }

    // Spec PW1: `\b` keeps the base's drive; `C:b` joins only an absolute base on the same drive.
    public String resolveRoot(String base, String other) {
        if (base == null) {
            return null;
        }
        if (other.equals("\\")) {
            return rootKind(base) == 2 ? null : base.substring(0, base.length() - (base.endsWith("\\") ? 1 : 0)) + "\\";
        }
        return rootKind(base) == 3 && base.regionMatches(true, 0, other, 0, 2) ? base : null;
    }

    public String absolutePath(Parsed p, String userDir) {
        StringBuilder rest = new StringBuilder();
        for (String name : p.names) {
            if (rest.length() > 0) {
                rest.append('\\');
            }
            rest.append(name);
        }
        String r = rest.toString();
        int kind = rootKind(p.root);
        if (kind == 2) {
            return drive(userDir) + "\\" + r;
        }
        if (kind == 1) {
            // Observed: a drive-relative path gets a separator even without names (`C:` ends in `\`).
            String dir = p.root.equalsIgnoreCase(drive(userDir)) ? userDir : p.root;
            if (dir == p.root && FileSystem.attributes(dir + "\\") == 0) {
                // Observed: a missing drive has no working directory.
                throw new java.io.IOError(new java.io.IOException(
                        "Unable to get working directory of drive '" + Character.toUpperCase(dir.charAt(0)) + "'"));
            }
            return dir.endsWith("\\") ? dir + r : dir + "\\" + r;
        }
        return resolveFile(userDir, r);
    }

    // Observed: file:///C:/x%20y/z for a drive, file://host/share/x for UNC.
    public String uriPath(String absolute) {
        String s = quotePath(absolute.replace('\\', '/'));
        return absolute.startsWith("\\\\") ? "file:" + s : "file:///" + s;
    }
}
