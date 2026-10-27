package lazy.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.PatternSyntaxException;
import lazy.internal.RegexNodes.Node;

/**
 * A backtracking regular expression engine for the subset of Java's syntax
 * that the class library needs before java.util.regex (S5-03) exists:
 * literals and escapes, `.`, classes with ranges and `\d \s \w` (and their
 * negations), groups `( )` and `(?: )`, `|`, greedy and lazy `* + ? {n,m}`,
 * `^ $ \b \B`, and `\Q…\E`. Anything else in Java's syntax is an
 * UnsupportedOperationException naming the construct, never a silent
 * mismatch. S5-03 grows java.util.regex.Pattern out of this engine.
 *
 * The pattern is parsed into pieces first, then linked back to front into
 * a chain of {@link RegexNodes} ending in an accepting node.
 */
public final class Regex {
    private final String pattern;
    private final boolean ignoreCase;
    private int pos;
    private int groups;
    private int loops;
    private final Node head;

    private Regex(String pattern, boolean ignoreCase) {
        this.pattern = pattern;
        this.ignoreCase = ignoreCase;
        List<List<Piece>> top = alternation();
        if (more()) {
            throw syntax("Unmatched closing ')'");
        }
        head = link(top, new RegexNodes.Accept());
    }

    /** Compiles `pattern`; `ignoreCase` compares chars by their case-folded forms. */
    public static Regex compile(String pattern, boolean ignoreCase) {
        if (pattern == null) {
            throw new NullPointerException();
        }
        return new Regex(pattern, ignoreCase);
    }

    public int groupCount() {
        return groups;
    }

    /** Whether the whole of `s` matches. */
    public boolean matches(CharSequence s) {
        return head.match(new RegexNodes.State(s, groups, loops, true), 0);
    }

    /**
     * The first match at or after `from`, as group bounds (start, end pairs,
     * group 0 first, -1 for a group that did not take part), or null.
     */
    public int[] find(CharSequence s, int from) {
        for (int i = from; i <= s.length(); i++) {
            RegexNodes.State st = new RegexNodes.State(s, groups, loops, false);
            if (head.match(st, i)) {
                st.groups[0] = i;
                st.groups[1] = st.end;
                return st.groups;
            }
        }
        return null;
    }

    static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == ' ' || c == ' ';
    }

    PatternSyntaxException syntax(String desc) {
        return new PatternSyntaxException(desc, pattern, pos);
    }

    UnsupportedOperationException notYet(String what) {
        return new UnsupportedOperationException("regular expression construct not supported yet: "
                + what + " in " + pattern);
    }

    boolean more() {
        return pos < pattern.length();
    }

    char peek() {
        return pattern.charAt(pos);
    }

    char next() {
        if (!more()) {
            throw syntax("Unexpected internal error");
        }
        return pattern.charAt(pos++);
    }

    boolean ignoreCase() {
        return ignoreCase;
    }

    /** One element of a sequence, linked once the rest of the chain is known. */
    private abstract static class Piece {
        abstract Node link(Node next);
    }

    /** A piece that matches exactly one char. */
    private static final class Single extends Piece {
        final RegexNodes.CharTest test;

        Single(RegexNodes.CharTest test) {
            this.test = test;
        }

        Node link(Node next) {
            RegexNodes.One one = new RegexNodes.One(test);
            one.next = next;
            return one;
        }
    }

    private static final class Anchored extends Piece {
        final char kind;

        Anchored(char kind) {
            this.kind = kind;
        }

        Node link(Node next) {
            RegexNodes.Anchor a = new RegexNodes.Anchor(kind);
            a.next = next;
            return a;
        }
    }

    /** Alternatives, possibly a capturing group (`number` > 0). */
    private static final class Group extends Piece {
        final List<List<Piece>> branches;
        final int number;

        Group(List<List<Piece>> branches, int number) {
            this.branches = branches;
            this.number = number;
        }

        Node link(Node next) {
            if (number <= 0) {
                return Regex.link(branches, next);
            }
            RegexNodes.Mark close = new RegexNodes.Mark(2 * number + 1);
            close.next = next;
            RegexNodes.Mark open = new RegexNodes.Mark(2 * number);
            open.next = Regex.link(branches, close);
            return open;
        }
    }

    private static final class Repeat extends Piece {
        final Piece atom;
        final int min;
        final int max;
        final boolean greedy;
        final int id;

        Repeat(Piece atom, int min, int max, boolean greedy, int id) {
            this.atom = atom;
            this.min = min;
            this.max = max;
            this.greedy = greedy;
            this.id = id;
        }

        Node link(Node next) {
            if (atom instanceof Single) {
                RegexNodes.Run run = new RegexNodes.Run(((Single) atom).test, min, max, greedy);
                run.next = next;
                return run;
            }
            RegexNodes.Loop loop = new RegexNodes.Loop(min, max, greedy, id);
            loop.next = next;
            loop.body = atom.link(new RegexNodes.Again(loop));
            return loop;
        }
    }

    static Node link(List<List<Piece>> branches, Node tail) {
        Node[] heads = new Node[branches.size()];
        for (int b = 0; b < heads.length; b++) {
            List<Piece> seq = branches.get(b);
            Node n = tail;
            for (int i = seq.size() - 1; i >= 0; i--) {
                n = seq.get(i).link(n);
            }
            heads[b] = n;
        }
        if (heads.length == 1) {
            return heads[0];
        }
        RegexNodes.Alt alt = new RegexNodes.Alt(heads);
        alt.next = tail;
        return alt;
    }

    /** sequence ('|' sequence)*, stopping at `)` or the end. */
    private List<List<Piece>> alternation() {
        List<List<Piece>> branches = new ArrayList<>();
        branches.add(sequence());
        while (more() && peek() == '|') {
            pos++;
            branches.add(sequence());
        }
        return branches;
    }

    private List<Piece> sequence() {
        List<Piece> pieces = new ArrayList<>();
        while (more() && peek() != '|' && peek() != ')') {
            pieces.add(quantified(atom()));
        }
        return pieces;
    }

    private Piece quantified(Piece atom) {
        if (!more()) {
            return atom;
        }
        char q = peek();
        int min;
        int max;
        if (q == '*' || q == '+' || q == '?') {
            pos++;
            min = q == '+' ? 1 : 0;
            max = q == '?' ? 1 : Integer.MAX_VALUE;
        } else if (q == '{') {
            pos++;
            min = number();
            max = min;
            if (more() && peek() == ',') {
                pos++;
                max = more() && Character.isDigit(peek()) ? number() : Integer.MAX_VALUE;
            }
            if (!more() || peek() != '}') {
                throw syntax("Unclosed counted closure");
            }
            pos++;
            if (max < min) {
                throw syntax("Illegal repetition range");
            }
        } else {
            return atom;
        }
        if (atom instanceof Anchored) {
            throw notYet("a quantified anchor");
        }
        boolean greedy = true;
        if (more() && peek() == '?') {
            pos++;
            greedy = false;
        } else if (more() && peek() == '+') {
            throw notYet("possessive quantifier");
        }
        return new Repeat(atom, min, max, greedy, loops++);
    }

    private int number() {
        if (!more() || !Character.isDigit(peek())) {
            throw syntax("Illegal repetition");
        }
        long n = 0;
        while (more() && Character.isDigit(peek())) {
            n = Math.min(Integer.MAX_VALUE, n * 10 + (peek() - '0'));
            pos++;
        }
        return (int) n;
    }

    private Piece atom() {
        char c = peek();
        switch (c) {
            case '(':
                return group();
            case '[':
                pos++;
                return new Single(RegexClass.bracket(this));
            case '.':
                pos++;
                return new Single(RegexClass.dot());
            case '^':
            case '$':
                pos++;
                return new Anchored(c);
            case '\\':
                return escaped();
            case '*':
            case '+':
            case '?':
                throw syntax("Dangling meta character '" + c + "'");
            case '{':
                throw syntax("Illegal repetition");
            default:
                pos++;
                return new Single(RegexClass.literal(c, ignoreCase));
        }
    }

    private Piece group() {
        pos++;
        int number;
        if (pattern.startsWith("?:", pos)) {
            pos += 2;
            number = 0;
        } else if (more() && peek() == '?') {
            throw notYet("group flags or lookaround");
        } else {
            number = ++groups;
        }
        List<List<Piece>> body = alternation();
        if (!more()) {
            throw syntax("Unclosed group");
        }
        pos++;
        return new Group(body, number);
    }

    private Piece escaped() {
        char c = pos + 1 < pattern.length() ? pattern.charAt(pos + 1) : 0;
        if (c == 'b' || c == 'B') {
            pos += 2;
            return new Anchored(c);
        }
        if (c == 'Q') {
            pos += 2;
            int end = pattern.indexOf("\\E", pos);
            String quoted = pattern.substring(pos, end < 0 ? pattern.length() : end);
            pos = end < 0 ? pattern.length() : end + 2;
            List<Piece> seq = new ArrayList<>();
            for (int i = 0; i < quoted.length(); i++) {
                seq.add(new Single(RegexClass.literal(quoted.charAt(i), ignoreCase)));
            }
            List<List<Piece>> one = new ArrayList<>();
            one.add(seq);
            return new Group(one, 0);
        }
        return new Single(RegexClass.escape(this));
    }
}
