package lazy.internal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.util.List;

/** Windows path rules: drives, UNC shares, `\` and `/` alike, names ignoring case (io.md W1, nio-file.md PW1). */
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
        // Observed: wildcards (and so `\\?\` names) have no canonical form either.
        if ((absolute.startsWith("\\\\") && split(absolute, 2, '\\').size() < 2)
                || absolute.indexOf('*') >= 0 || absolute.indexOf('?') >= 0) {
            throw new IOException("Invalid argument");
        }
        // Observed: once "." and ".." are applied, a name of three or more dots before another
        // name has no canonical form.
        List<String> names = resolved(absolute, 0);
        for (int i = 0; i < names.size() - 1; i++) {
            if (names.get(i).length() >= 3 && names.get(i).replace(".", "").trim().isEmpty() && names.get(i).contains(".")) {
                throw new IOException("Bad pathname");
            }
        }
        return absolute;
    }

    /** The names of `path` from `from`, with "." dropped and ".." taking the name before it. */
    private static List<String> resolved(String path, int from) {
        List<String> names = new java.util.ArrayList<>();
        for (String n : split(path, from, '\\')) {
            if (n.equals("..")) {
                if (!names.isEmpty()) {
                    names.remove(names.size() - 1);
                }
            } else if (!n.equals(".")) {
                names.add(n);
            }
        }
        return names;
    }

    // Observed: outside a UNC share, a ':' in the first name (after "." and ".." are applied)
    // has no canonical form.
    public void checkGiven(String path) throws IOException {
        if (path.startsWith("\\\\")) {
            return;
        }
        List<String> names = resolved(path, prefixLength(path));
        if (!names.isEmpty() && names.get(0).indexOf(':') >= 0) {
            throw new IOException("The filename, directory name, or volume label syntax is incorrect");
        }
    }

    // Spec W1: the canonical drive letter is upper case.
    // Observed: a UNC host and share keep the case they were given.
    String postcanonical(String absolute, String canonical) {
        if (absolute.startsWith("\\\\") && canonical.startsWith("\\\\")) {
            int a = nth(absolute, 2);
            int c = nth(canonical, 2);
            if (a > 0 && c > 0 && absolute.substring(0, a).equalsIgnoreCase(canonical.substring(0, c))) {
                return absolute.substring(0, a) + canonical.substring(c);
            }
            return canonical;
        }
        if (hasDrive(canonical)) {
            return Character.toUpperCase(canonical.charAt(0)) + canonical.substring(1);
        }
        return canonical;
    }

    /** The index of the `n`th separator after the leading `\\`, or the length. */
    private static int nth(String s, int n) {
        int i = 2;
        for (int k = 0; k < n; k++) {
            int j = s.indexOf('\\', i);
            if (j < 0) {
                return k == n - 1 ? s.length() : -1;
            }
            i = j + 1;
        }
        return i - 1;
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
        if (!absolute.startsWith("\\\\")) {
            return "file:///" + s;
        }
        // Observed: a host that is not a host name keeps the share under an empty authority.
        int end = absolute.indexOf('\\', 2);
        return hostName(absolute.substring(2, end < 0 ? absolute.length() : end)) ? "file:" + s : "file://" + s;
    }

    /** RFC 2396 host names: dot-separated labels of letters, digits and inner '-'; or IPv4. */
    private static boolean hostName(String host) {
        String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        boolean numeric = true;
        int labels = 0;
        char top = 0;
        for (String l : split(h, 0, '.')) {
            if (l.startsWith("-") || l.endsWith("-")) {
                return false;
            }
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                boolean alnum = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                if (!alnum && c != '-') {
                    return false;
                }
                numeric &= c >= '0' && c <= '9';
            }
            labels++;
            top = l.charAt(0);
        }
        if (labels == 0 || h.contains("..") || h.startsWith(".")) {
            return false;
        }
        return numeric ? labels == 4 : Character.isLetter(top);
    }
}
