package java.util.regex;

/** Parses a pattern into a node tree: alternation, groups, quantifiers, escapes. */
final class Parser extends GroupReader {
    private static final int MAX = Integer.MAX_VALUE;

    int loopCount;
    /** Whether the atom just parsed was a parenthesized group (Lengths treats its loops differently). */
    private boolean atomWasGroup;

    Parser(String pattern, int flags) {
        super(pattern, flags);
    }

    Node parse() {
        if (has(Pattern.LITERAL)) {
            return literalPattern();
        }
        Node n = expr();
        if (!atEnd()) {
            if (cur() != ')') {
                // Only a lone backslash at the very end stops the parse here.
                cursor = len + 1;
                throw error("Unexpected internal error");
            }
            throw error("Unmatched closing ')'");
        }
        return n;
    }

    private Node literalPattern() {
        Node[] items = new Node[src.length()];
        int n = 0;
        int i = 0;
        while (i < len) {
            int cp = src.codePointAt(i);
            i += Character.charCount(cp);
            items[n++] = literal(cp);
        }
        return sequenceOf(items, n);
    }

    Node expr() {
        Node[] branches = new Node[4];
        int n = 0;
        branches[n++] = sequence();
        while (true) {
            skipComments();
            if (atEnd() || src.charAt(cursor) != '|') {
                break;
            }
            cursor++;
            if (n == branches.length) {
                Node[] bigger = new Node[n * 2];
                System.arraycopy(branches, 0, bigger, 0, n);
                branches = bigger;
            }
            branches[n++] = sequence();
        }
        if (n == 1) {
            return branches[0];
        }
        Node[] exact = new Node[n];
        System.arraycopy(branches, 0, exact, 0, n);
        return alternation(exact);
    }

    /** Alternatives that are each one char of a class are one class, which runs without recursion. */
    private static Node alternation(Node[] branches) {
        CharClass union = null;
        for (Node b : branches) {
            if (!(b instanceof Atoms.One)) {
                return new Node.Alt(branches);
            }
            CharClass cc = ((Atoms.One) b).cc;
            union = union == null ? cc : CharClass.or(union, cc);
        }
        return new Atoms.One(CharClass.cached(union));
    }

    private Node sequence() {
        Node[] items = new Node[8];
        int n = 0;
        while (true) {
            skipComments();
            if (atEnd()) {
                break;
            }
            char c = src.charAt(cursor);
            if (c == '|' || c == ')' || (c == '\\' && cursor + 1 == len)) {
                break;
            }
            Node atom = atom();
            if (atom == null) {
                continue;
            }
            atom = quantify(atom);
            if (n == items.length) {
                Node[] bigger = new Node[n * 2];
                System.arraycopy(items, 0, bigger, 0, n);
                items = bigger;
            }
            items[n++] = atom;
        }
        return sequenceOf(items, n);
    }

    /** Builds a sequence, merging runs of plain literal chars into one string node. */
    private Node sequenceOf(Node[] items, int n) {
        Node[] out = new Node[n];
        int m = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            StringBuilder run = new StringBuilder();
            while (j < n && items[j] instanceof Atoms.One && ((Atoms.One) items[j]).literal >= 0) {
                run.appendCodePoint(((Atoms.One) items[j]).literal);
                j++;
            }
            if (j - i >= 2) {
                out[m++] = new Atoms.Str(run.toString().toCharArray());
                i = j;
            } else {
                out[m++] = items[i++];
            }
        }
        if (m == 0) {
            return new Node.Empty();
        }
        if (m == 1) {
            return out[0];
        }
        Node[] exact = new Node[m];
        System.arraycopy(out, 0, exact, 0, m);
        return new Node.Seq(exact);
    }

    private Node atom() {
        char c = src.charAt(cursor);
        atomWasGroup = false;
        switch (c) {
            case '(':
                cursor++;
                Node g = group();
                atomWasGroup = !(g instanceof GroupNodes.Look || g instanceof GroupNodes.Atomic);
                return g;
            case '[':
                cursor++;
                return new Atoms.One(parseClass());
            case '\\':
                cursor++;
                return escape();
            case '^':
                cursor++;
                return has(Pattern.MULTILINE) ? new Anchor(Anchor.BEGIN_LINE, has(Pattern.UNIX_LINES))
                    : new Anchor(Anchor.BEGIN, false);
            case '$':
                cursor++;
                return new Anchor(has(Pattern.MULTILINE) ? Anchor.END_LINE : Anchor.END_INPUT_OR_FINAL,
                    has(Pattern.UNIX_LINES));
            case '.':
                cursor++;
                return new Atoms.One(dot());
            case '{':
                return new Node.Empty();
            case '*':
            case '+':
            case '?':
                cursor++;
                skipComments();
                throw error("Dangling meta character '" + c + "'");
            default:
                return literal(readCodePoint());
        }
    }

    private CharClass dot() {
        if (has(Pattern.DOTALL)) {
            return CharClass.of(CharClass.ANY);
        }
        return CharClass.of(has(Pattern.UNIX_LINES) ? CharClass.DOT_UNIX : CharClass.DOT);
    }

    private Atoms.One literal(int cp) {
        if (has(Pattern.CASE_INSENSITIVE)) {
            return new Atoms.One(CharClass.foldRange(cp, cp, has(Pattern.UNICODE_CASE)));
        }
        Atoms.One one = new Atoms.One(CharClass.single(cp));
        one.literal = cp;
        return one;
    }

    // Escapes outside classes.

    private Node escape() {
        int e = read();
        if (e == 0 && cursor > len) {
            throw error("Unexpected internal error");
        }
        int ch = escapedChar(e);
        if (ch >= 0) {
            return literal(ch);
        }
        CharClass predefined = predefined(e);
        if (predefined != null) {
            return new Atoms.One(predefined);
        }
        switch (e) {
            case 'p':
            case 'P':
                return new Atoms.One(property(e == 'P'));
            case 'b': return new Anchor(Anchor.WORD, false);
            case 'B': return new Anchor(Anchor.NOT_WORD, false);
            case 'A': return new Anchor(Anchor.BEGIN, false);
            case 'Z': return new Anchor(Anchor.END_INPUT_OR_FINAL, has(Pattern.UNIX_LINES));
            case 'z': return new Anchor(Anchor.END_INPUT, false);
            case 'G': return new Anchor(Anchor.LAST_MATCH, false);
            case 'R': return new Atoms.LineBreak();
            case 'k': return namedBackReference();
            default:
                break;
        }
        if (e >= '1' && e <= '9') {
            return backReference(e - '0');
        }
        if (isAsciiLetter(e)) {
            throw error("Illegal/unsupported escape sequence");
        }
        if (Character.isHighSurrogate((char) e) && !atEnd() && Character.isLowSurrogate((char) cur())) {
            return literal(Character.toCodePoint((char) e, (char) read()));
        }
        return literal(e);
    }

    private Node backReference(int first) {
        int ref = first;
        while (!atEnd() && cur() >= '0' && cur() <= '9') {
            int wider = ref * 10 + (cur() - '0');
            if (wider > groupCount) {
                break;
            }
            ref = wider;
            cursor++;
        }
        return new Atoms.BackRef(ref, has(Pattern.CASE_INSENSITIVE), has(Pattern.UNICODE_CASE));
    }

    private Node namedBackReference() {
        skipComments();
        if (read() != '<') {
            throw error("\\k is not followed by '<' for named capturing group");
        }
        StringBuilder name = new StringBuilder();
        int ch = read();
        name.append((char) ch);
        while (true) {
            ch = read();
            if (!isNameChar(ch)) {
                break;
            }
            name.append((char) ch);
        }
        if (ch != '>') {
            throw error("named capturing group is missing trailing '>'");
        }
        int group = groupNumber(name.toString());
        if (group < 0) {
            throw error("(named capturing group <" + name + "> does not exit");
        }
        return new Atoms.BackRef(group, has(Pattern.CASE_INSENSITIVE), has(Pattern.UNICODE_CASE));
    }

    // Quantifiers.

    private Node quantify(Node atom) {
        skipComments();
        if (atEnd()) {
            return atom;
        }
        switch (cur()) {
            case '?':
                cursor++;
                return suffix(atom, 0, 1, true);
            case '*':
                cursor++;
                return suffix(atom, 0, MAX, false);
            case '+':
                cursor++;
                return suffix(atom, 1, MAX, false);
            case '{':
                return counted(atom);
            default:
                return atom;
        }
    }

    /** {n}, {n,} or {n,m} with the cursor on the '{'. */
    private Node counted(Node atom) {
        int open = cursor;
        cursor++;
        if (atEnd() || cur() < '0' || cur() > '9') {
            throw errorAt("Illegal repetition", open - 1);
        }
        int min = digits();
        int max = min;
        int ch;
        if (!atEnd() && cur() == ',') {
            cursor++;
            if (!atEnd() && cur() == '}') {
                max = MAX;
            } else if (!atEnd() && cur() >= '0' && cur() <= '9') {
                max = digits();
            }
            skipComments();
            ch = read();
        } else {
            skipComments();
            ch = read();
        }
        if (ch != '}') {
            throw error("Unclosed counted closure");
        }
        if (min < 0 || max < 0 || max < min) {
            throw error("Illegal repetition range");
        }
        return suffix(atom, min, max, false);
    }

    /** Decimal digits as an int, wrapping on overflow as the reference does. */
    private int digits() {
        int v = 0;
        while (!atEnd() && cur() >= '0' && cur() <= '9') {
            v = v * 10 + (read() - '0');
        }
        return v;
    }

    /** Applies a trailing '?' (lazy) or '+' (possessive) and builds the repeat. */
    private Node suffix(Node atom, int min, int max, boolean question) {
        int mode = Repeat.GREEDY;
        skipComments();
        if (!atEnd() && cur() == '?') {
            cursor++;
            mode = Repeat.LAZY;
        } else if (!atEnd() && cur() == '+') {
            cursor++;
            mode = Repeat.POSSESSIVE;
        }
        if (min == 0 && max > 0 && mode == Repeat.GREEDY && !question && isEmptyGroup(atom)) {
            // A greedy optional group with a fixed zero-width body runs its body but never
            // records a capture; the reference implementation does the same (but not for '?').
            return new Node.Alt(new Node[] {((GroupNodes.Group) atom).body(), new Node.Empty()});
        }
        if (atom instanceof Atoms.One) {
            return new Repeat.CharRepeat(((Atoms.One) atom).cc, min, max, mode);
        }
        Node loop = new Repeat.Loop(atom, min, max, mode == Repeat.LAZY, loopCount++,
            atomWasGroup && !question && mode != Repeat.POSSESSIVE);
        return mode == Repeat.POSSESSIVE ? new GroupNodes.Atomic(loop) : loop;
    }

    /** A capturing group whose body can only match the empty string, with no loops or branches. */
    private static boolean isEmptyGroup(Node atom) {
        return atom instanceof GroupNodes.Group && isFixedEmpty(((GroupNodes.Group) atom).body());
    }

    private static boolean isFixedEmpty(Node n) {
        if (n instanceof Node.Seq) {
            for (Node item : ((Node.Seq) n).items) {
                if (!isFixedEmpty(item)) {
                    return false;
                }
            }
            return true;
        }
        if (n instanceof GroupNodes.Group) {
            return isFixedEmpty(((GroupNodes.Group) n).body());
        }
        if (n instanceof Repeat.Loop) {
            Repeat.Loop l = (Repeat.Loop) n;
            return l.minCount() == l.maxCount() && isFixedEmpty(l.body());
        }
        boolean zeroWidth = n instanceof Anchor || n instanceof GroupNodes.Look || n instanceof Node.Empty
            || n instanceof Repeat.CharRepeat;
        return zeroWidth && n.max() == 0;
    }
}
