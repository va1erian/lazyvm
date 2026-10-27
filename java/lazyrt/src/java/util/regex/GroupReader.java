package java.util.regex;

/**
 * Group syntax: the part of a group from its '(' to the start of its body, for
 * capturing, named, non-capturing, lookaround, atomic and inline-flag groups.
 * The body and the closing ')' are handled by the Parser's explicit stack, so
 * nesting depth does not use Java frames.
 */
abstract class GroupReader extends ClassReader {
    // What an open group will turn into when its ')' arrives.
    static final int TOP = 0, CAPTURE = 1, PLAIN = 2, LOOKAHEAD = 3, LOOKBEHIND = 4, ATOMIC = 5, NOT_A_GROUP = 6;

    /** An open group: the alternatives and items collected so far. */
    static final class Frame {
        final int kind;
        final int savedFlags;
        final int number;
        final boolean positive;
        Node[] branches = new Node[2];
        int branchCount;
        Node[] items = new Node[4];
        int itemCount;

        Frame(int kind, int savedFlags, int number, boolean positive) {
            this.kind = kind;
            this.savedFlags = savedFlags;
            this.number = number;
            this.positive = positive;
        }
    }

    int groupCount;
    String[] groupNames = new String[4];

    GroupReader(String pattern, int flags) {
        super(pattern, flags);
    }

    static boolean isNameChar(int c) {
        return isAsciiLetter(c) || (c >= '0' && c <= '9');
    }

    final int groupNumber(String name) {
        return groupNumber(groupNames, groupCount, name);
    }

    /** The number of the named group among groups 1..count, or -1. */
    static int groupNumber(String[] names, int count, String name) {
        for (int n = 1; n <= count; n++) {
            if (name.equals(names[n])) {
                return n;
            }
        }
        return -1;
    }

    /**
     * Reads what follows a '(' and returns the group to open, or null for an inline-flag group
     * `(?i)`, which only changes the flags for the rest of the enclosing group.
     */
    final Frame openGroup() {
        int saved = flags;
        skipComments();
        if (atEnd() || cur() != '?') {
            return new Frame(CAPTURE, saved, newGroup(null), true);
        }
        cursor++;
        int c = read();
        switch (c) {
            case ':':
                return new Frame(PLAIN, saved, 0, true);
            case '=':
            case '!':
                return new Frame(LOOKAHEAD, saved, 0, c == '=');
            case '>':
                return new Frame(ATOMIC, saved, 0, true);
            case '<':
                return angleGroup(saved);
            case '$':
            case '@':
                throw error("Unknown group type");
            default:
                cursor--;
                return flagGroup(saved);
        }
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

    /** After "(?<": a lookbehind or a named group. */
    private Frame angleGroup(int saved) {
        int c = readSkipping();
        if (c == '=' || c == '!') {
            return new Frame(LOOKBEHIND, saved, 0, c == '=');
        }
        if (!isAsciiLetter(c)) {
            return new Frame(NOT_A_GROUP, saved, 0, true);
        }
        StringBuilder name = new StringBuilder();
        name.append((char) c);
        while (true) {
            c = readSkipping();
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
        return new Frame(CAPTURE, saved, newGroup(s), true);
    }

    /** After "(?" with the cursor on the first modifier char; null for (?flags), a group for (?flags:x). */
    private Frame flagGroup(int saved) {
        boolean on = true;
        while (true) {
            int c = readSkipping();
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
                case ':': return new Frame(PLAIN, saved, 0, true);
                default:
                    throw error("Unknown inline modifier");
            }
        }
    }

    private void setFlag(int flag, boolean on) {
        flags = on ? flags | flag : flags & ~flag;
    }

    /** At the end of the pattern, or at a lone trailing backslash, which counts as one char more. */
    final PatternSyntaxException unclosed() {
        cursor = atEnd() ? len + 1 : len + 2;
        return error("Unclosed group");
    }
}
