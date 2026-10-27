package java.util.regex;

/**
 * Group syntax: capturing, named, non-capturing, lookaround, atomic and inline
 * flag groups. The expression inside a group is parsed by the subclass.
 */
abstract class GroupReader extends ClassReader {
    int groupCount;
    String[] groupNames = new String[4];

    GroupReader(String pattern, int flags) {
        super(pattern, flags);
    }

    /** Parses alternatives up to a ')' or the end of the pattern. */
    abstract Node expr();

    static boolean isNameChar(int c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    final int groupNumber(String name) {
        for (int n = 1; n <= groupCount; n++) {
            if (name.equals(groupNames[n])) {
                return n;
            }
        }
        return -1;
    }

    // Groups.

    final Node group() {
        int saved = flags;
        Node result;
        if (!atEnd() && cur() == '?') {
            cursor++;
            int c = read();
            switch (c) {
                case ':':
                    result = body();
                    break;
                case '=':
                    result = new GroupNodes.Look(body(), true, true);
                    break;
                case '!':
                    result = new GroupNodes.Look(body(), true, false);
                    break;
                case '>':
                    result = new GroupNodes.Atomic(body());
                    break;
                case '<':
                    result = angleGroup();
                    break;
                case '$':
                case '@':
                    throw error("Unknown group type");
                default:
                    cursor--;
                    result = flagGroup(saved);
                    break;
            }
        } else {
            int n = newGroup(null);
            result = new GroupNodes.Group(n, body());
        }
        if (result != null) {
            flags = saved;
        }
        return result;
    }

    /** The contents of a group up to and including its ')'. */
    private Node body() {
        Node n = expr();
        if (atEnd() || cur() != ')') {
            unclosed();
        }
        cursor++;
        return n;
    }

    /** At the end of the pattern, or at a lone trailing backslash, which counts as one char more. */
    private void unclosed() {
        cursor = atEnd() ? len + 1 : len + 2;
        throw error("Unclosed group");
    }

    private int newGroup(String name) {
        groupCount++;
        if (groupCount == groupNames.length) {
            String[] bigger = new String[groupCount * 2];
            System.arraycopy(groupNames, 0, bigger, 0, groupCount);
            groupNames = bigger;
        }
        groupNames[groupCount] = name;
        return groupCount;
    }

    /** The body of a lookbehind, which must have a maximum length (see Lengths). */
    private Node lookbehindBody() {
        Node n = expr();
        if (!Lengths.hasMaxLength(n)) {
            throw error("Look-behind group does not have an obvious maximum length");
        }
        if (atEnd() || cur() != ')') {
            unclosed();
        }
        cursor++;
        return n;
    }

    /** After "(?<": a lookbehind or a named group. */
    private Node angleGroup() {
        int c = read();
        if (c == '=' || c == '!') {
            return new GroupNodes.Look(lookbehindBody(), false, c == '=');
        }
        if (!isAsciiLetter(c)) {
            // Parsed as a lookbehind whose first char is neither '=' nor '!'.
            Node unknown = expr();
            throw error(Lengths.hasMaxLength(unknown)
                ? "Unknown look-behind group"
                : "Look-behind group does not have an obvious maximum length");
        }
        StringBuilder name = new StringBuilder();
        name.append((char) c);
        while (true) {
            c = read();
            if (!isNameChar(c)) {
                break;
            }
            name.append((char) c);
        }
        if (c != '>') {
            throw error("named capturing group is missing trailing '>'");
        }
        String s = name.toString();
        if (groupNumber(s) >= 0) {
            throw error("Named capturing group <" + s + "> is already defined");
        }
        int n = newGroup(s);
        return new GroupNodes.Group(n, body());
    }

    /**
     * After "(?" with the cursor on the first modifier char: (?flags) changes the flags for the rest of
     * the enclosing group and returns null; (?flags:x) scopes them to x.
     */
    private Node flagGroup(int saved) {
        boolean on = true;
        while (true) {
            int c = read();
            switch (c) {
                case 'i': setFlag(Pattern.CASE_INSENSITIVE, on); break;
                case 'd': setFlag(Pattern.UNIX_LINES, on); break;
                case 'm': setFlag(Pattern.MULTILINE, on); break;
                case 's': setFlag(Pattern.DOTALL, on); break;
                case 'u': setFlag(Pattern.UNICODE_CASE, on); break;
                case 'c': setFlag(Pattern.CANON_EQ, on); break;
                case 'x': setFlag(Pattern.COMMENTS, on); break;
                case 'U': setFlag(Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE, on); break;
                case '-': on = false; break;
                case ')': return null;
                case ':':
                    Node n = body();
                    flags = saved;
                    return n;
                default:
                    throw error("Unknown inline modifier");
            }
        }
    }

    private void setFlag(int flag, boolean on) {
        flags = on ? flags | flag : flags & ~flag;
    }
}
