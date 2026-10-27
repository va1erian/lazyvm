package java.util.regex;

import java.util.function.Predicate;

/**
 * A compiled regular expression: a backtracking matcher over a tree of nodes
 * (Parser builds it, Node and its subclasses run it).
 *
 * <p>CANON_EQ is accepted and ignored: canonical equivalence is not
 * implemented (docs/regex.md). Costs follow from backtracking: nested open
 * quantifiers such as (a+)+b take exponential time on non-matching input, as
 * they do in other backtracking engines; possessive quantifiers and atomic
 * groups avoid that, and loops over a single character class use no stack per
 * character.
 */
public final class Pattern implements java.io.Serializable {
    public static final int UNIX_LINES = 0x01;
    public static final int CASE_INSENSITIVE = 0x02;
    public static final int COMMENTS = 0x04;
    public static final int MULTILINE = 0x08;
    public static final int LITERAL = 0x10;
    public static final int DOTALL = 0x20;
    public static final int UNICODE_CASE = 0x40;
    public static final int CANON_EQ = 0x80;
    public static final int UNICODE_CHARACTER_CLASS = 0x100;

    private static final long serialVersionUID = 5073258162644648461L;

    private final String pattern;
    private int flags;

    transient Node root;
    transient int groupCount;
    transient int loopCount;
    transient int minLength;
    /** The pattern starts with ^ or \A, so a search only needs to try where it starts. */
    transient boolean anchoredStart;
    private transient String[] groupNames;

    private Pattern(String p, int f) {
        if (p == null) {
            throw new NullPointerException();
        }
        pattern = p;
        flags = f;
        if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
            flags |= UNICODE_CASE;
        }
        compile();
    }

    private void compile() {
        Parser parser = new Parser(pattern, flags);
        Node tree = parser.parse();
        flags = parser.flags;
        groupCount = parser.groupCount;
        loopCount = parser.loopCount;
        groupNames = parser.groupNames;
        minLength = tree.min();
        anchoredStart = parser.anchoredStart;
        root = tree.link(new Node.Accept());
    }

    public static Pattern compile(String regex) {
        return new Pattern(regex, 0);
    }

    public static Pattern compile(String regex, int flags) {
        return new Pattern(regex, flags);
    }

    public String pattern() {
        return pattern;
    }

    public String toString() {
        return pattern;
    }

    public int flags() {
        return flags;
    }

    public Matcher matcher(CharSequence input) {
        if (input == null) {
            throw new NullPointerException();
        }
        return new Matcher(this, input);
    }

    public static boolean matches(String regex, CharSequence input) {
        return compile(regex).matcher(input).matches();
    }

    public Predicate<String> asPredicate() {
        return new Predicate<String>() {
            public boolean test(String s) {
                return matcher(s).find();
            }
        };
    }

    /** The group number of a named group, or -1. */
    int groupNumber(String name) {
        return GroupReader.groupNumber(groupNames, groupCount, name);
    }

    public String[] split(CharSequence input) {
        return split(input, 0);
    }

    /**
     * Splits around matches. A zero-width match at the very start never yields an
     * empty leading piece; with limit 0 trailing empty pieces are dropped; a
     * positive limit caps the pieces, the last holding the unsplit rest.
     */
    public String[] split(CharSequence input, int limit) {
        int index = 0;
        boolean limited = limit > 0;
        String[] pieces = new String[16];
        int count = 0;
        Matcher m = matcher(input);
        while (m.find()) {
            if (!limited || count < limit - 1) {
                if (index == 0 && m.start() == 0 && m.end() == 0) {
                    continue;
                }
                pieces = add(pieces, count++, input.subSequence(index, m.start()).toString());
                index = m.end();
            } else if (count == limit - 1) {
                pieces = add(pieces, count++, input.subSequence(index, input.length()).toString());
                index = m.end();
            }
        }
        if (index == 0) {
            return new String[] {input.toString()};
        }
        if (!limited || count < limit) {
            pieces = add(pieces, count++, input.subSequence(index, input.length()).toString());
        }
        int size = count;
        if (limit == 0) {
            while (size > 0 && pieces[size - 1].length() == 0) {
                size--;
            }
        }
        String[] result = new String[size];
        System.arraycopy(pieces, 0, result, 0, size);
        return result;
    }

    private static String[] add(String[] pieces, int at, String s) {
        if (at == pieces.length) {
            String[] bigger = new String[at * 2];
            System.arraycopy(pieces, 0, bigger, 0, at);
            pieces = bigger;
        }
        pieces[at] = s;
        return pieces;
    }

    /** A pattern string that matches `s` literally. */
    public static String quote(String s) {
        int end = s.indexOf("\\E");
        if (end == -1) {
            return "\\Q" + s + "\\E";
        }
        StringBuilder sb = new StringBuilder(s.length() * 2);
        sb.append("\\Q");
        int current = 0;
        while ((end = s.indexOf("\\E", current)) != -1) {
            sb.append(s, current, end);
            current = end + 2;
            sb.append("\\E\\\\E\\Q");
        }
        sb.append(s, current, s.length());
        sb.append("\\E");
        return sb.toString();
    }
}
