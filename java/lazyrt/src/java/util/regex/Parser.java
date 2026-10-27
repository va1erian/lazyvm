package java.util.regex;

/**
 * Parses a pattern into a node tree: alternation, groups, quantifiers, escapes.
 * Open groups live on an explicit stack, so deeply nested groups cost heap, not
 * Java frames.
 */
final class Parser extends GroupReader {
    private static final int MAX = Integer.MAX_VALUE;

    int loopCount;
    int lookCount;
    /** Whether the pattern's first top-level atom is ^ or \A and it has no top-level alternative. */
    boolean anchoredStart;
    private boolean topStartsWithBegin;
    /** Whether the atom just parsed was a (?:..) or capturing group (LookbehindReach treats its loops differently). */
    private boolean atomWasGroup;
    /** Whether the atom just parsed was a capturing group. */
    private boolean atomCapturing;
    /** Whether the atom just parsed came out of a parenthesized group of any kind. */
    private boolean atomFromGroup;

    Parser(String pattern, int flags) {
        super(pattern, flags);
    }

    Node parse() {
        if (has(Pattern.LITERAL)) {
            return literalPattern();
        }
        Frame[] stack = new Frame[16];
        int depth = 0;
        Frame cur = new Frame(TOP, flags, 0, true);
        stack[0] = cur;
        while (true) {
            skipComments();
            if (atEnd() || (src.charAt(cursor) == '\\' && cursor + 1 == len)) {
                if (depth == 0) {
                    break;
                }
                throw endInside(cur);
            }
            char c = src.charAt(cursor);
            if (c == '|') {
                cursor++;
                endBranch(cur);
            } else if (c == ')') {
                if (depth == 0) {
                    break;
                }
                Node closed = close(cur);
                cur = stack[--depth];
                add(cur, quantify(closed));
            } else if (c == '(') {
                cursor++;
                Frame f = openGroup();
                if (f != null) {
                    if (++depth == stack.length) {
                        Frame[] bigger = new Frame[depth * 2];
                        System.arraycopy(stack, 0, bigger, 0, depth);
                        stack = bigger;
                    }
                    stack[depth] = f;
                    cur = f;
                }
            } else {
                add(cur, quantify(atom()));
            }
        }
        if (!atEnd()) {
            if (cur() != ')') {
                // Only a lone backslash at the very end stops the parse here.
                cursor = len + 1;
                throw error("Unexpected internal error");
            }
            throw error("Unmatched closing ')'");
        }
        Node top = finish(cur);
        anchoredStart = topStartsWithBegin && cur.branchCount == 1;
        return top;
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
        return Merge.sequenceOf(items, n);
    }

    // The open groups.

    private void add(Frame f, Node n) {
        if (f.kind == TOP && f.branchCount == 0 && f.itemCount == 0) {
            // Observed on the oracle (RegexRegions, "anchored start"): only a ^ or \A written
            // directly at the start of the pattern lets a search try a single position; one
            // inside any group, even (?:^), does not.
            topStartsWithBegin = n instanceof Anchor && ((Anchor) n).isBegin() && !atomFromGroup;
        }
        if (f.itemCount == f.items.length) {
            Node[] bigger = new Node[f.itemCount * 2];
            System.arraycopy(f.items, 0, bigger, 0, f.itemCount);
            f.items = bigger;
        }
        f.items[f.itemCount++] = n;
    }

    private void endBranch(Frame f) {
        Node branch = Merge.sequenceOf(f.items, f.itemCount);
        f.itemCount = 0;
        if (f.branchCount == f.branches.length) {
            Node[] bigger = new Node[f.branchCount * 2];
            System.arraycopy(f.branches, 0, bigger, 0, f.branchCount);
            f.branches = bigger;
        }
        f.branches[f.branchCount++] = branch;
    }

    /** The node for everything collected in the group so far. */
    private Node finish(Frame f) {
        endBranch(f);
        if (f.branchCount == 1) {
            return f.branches[0];
        }
        Node[] exact = new Node[f.branchCount];
        System.arraycopy(f.branches, 0, exact, 0, f.branchCount);
        return Merge.alternation(exact);
    }

    /** Handles the ')' that ends group f (the cursor is on it) and returns the group's node. */
    private Node close(Frame f) {
        Node body = finish(f);
        Node result;
        switch (f.kind) {
            case CAPTURE:
                result = new GroupNodes.Group(f.number, body);
                break;
            case LOOKAHEAD:
                result = new GroupNodes.Look(body, true, f.positive, 0, lookCount++);
                break;
            case LOOKBEHIND:
                LookbehindReach reach = LookbehindReach.of(body);
                if (!reach.valid) {
                    throw error(NO_MAX_LENGTH);
                }
                result = new GroupNodes.Look(body, false, f.positive, reach.max, lookCount++);
                break;
            case ATOMIC:
                result = atomic(body);
                break;
            case NOT_A_GROUP:
                throw error(unknownGroupMessage(body));
            default:
                result = body;
                break;
        }
        cursor++;
        flags = f.savedFlags;
        atomWasGroup = f.kind == CAPTURE || f.kind == PLAIN;
        atomCapturing = f.kind == CAPTURE;
        atomFromGroup = true;
        return result;
    }

    private static final String NO_MAX_LENGTH = "Look-behind group does not have an obvious maximum length";

    private static String unknownGroupMessage(Node body) {
        return LookbehindReach.of(body).valid ? "Unknown look-behind group" : NO_MAX_LENGTH;
    }

    /** The error for reaching the end of the pattern inside group f. */
    private PatternSyntaxException endInside(Frame f) {
        if (f.kind == LOOKBEHIND && !LookbehindReach.of(finish(f)).valid) {
            return error(NO_MAX_LENGTH);
        }
        if (f.kind == NOT_A_GROUP) {
            return error(unknownGroupMessage(finish(f)));
        }
        return unclosed();
    }

    /** (?>x): x alone when it can match only one way, which needs no atomic wrapper. */
    private static Node atomic(Node body) {
        if (body instanceof Atoms.One) {
            return new Atoms.One(((Atoms.One) body).cc);
        }
        if (body instanceof Atoms.Str || body instanceof Node.Empty || body instanceof Anchor) {
            return body;
        }
        return new GroupNodes.Atomic(body, true);
    }

    private Atoms.One classAtom() {
        wideClass = false;
        Atoms.One one = new Atoms.One(parseClass());
        one.width = rawSurrogates && wideClass ? 2 : 1;
        return one;
    }

    // Atoms other than groups.

    private Node atom() {
        char c = src.charAt(cursor);
        atomWasGroup = false;
        atomCapturing = false;
        atomFromGroup = false;
        switch (c) {
            case '[':
                cursor++;
                return classAtom();
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
        Atoms.One one = plainLiteral(cp);
        one.width = rawSurrogates && cp > 0xFFFF ? 2 : 1;
        return one;
    }

    private Atoms.One plainLiteral(int cp) {
        if (has(Pattern.CASE_INSENSITIVE)) {
            Atoms.One folded = new Atoms.One(CaseFold.single(cp, has(Pattern.UNICODE_CASE)));
            folded.foldedLiteral = true;
            return folded;
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
        boolean wordClasses = has(Pattern.UNICODE_CHARACTER_CLASS);
        switch (e) {
            case 'p':
            case 'P':
                return new Atoms.One(property(e == 'P'));
            case 'b': return new Anchor(Anchor.WORD, false, wordClasses);
            case 'B': return new Anchor(Anchor.NOT_WORD, false, wordClasses);
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
        int ch = readCodePoint();
        name.appendCodePoint(ch);
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

    /** Decimal digits as an int, wrapping on overflow (a{2147483648} is rejected, as observed on the oracle). */
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
        if (min == 0 && max > 0 && mode == Repeat.GREEDY && !question && skipsEmptyCapture(atom)) {
            // Observed on the oracle (RegexQuantifiers, "optional empty group"): the body runs,
            // but the group is not recorded.
            return new Node.Alt(new Node[] {((GroupNodes.Group) atom).body(), new Node.Empty()});
        }
        int group = -1;
        Node single = atom;
        if (atom instanceof GroupNodes.Group && ((GroupNodes.Group) atom).body() instanceof Atoms.One) {
            group = ((GroupNodes.Group) atom).number();
            single = ((GroupNodes.Group) atom).body();
        }
        if (single instanceof Atoms.One) {
            return new Repeat.CharRepeat(((Atoms.One) single).cc, min, max, mode, group,
                ((Atoms.One) single).fromAlternation, question, atomWasGroup || group >= 0);
        }
        Node loop = new Repeat.Loop(atom, min, max, mode == Repeat.LAZY, loopCount++,
            atomWasGroup && !question && mode != Repeat.POSSESSIVE, question, mode == Repeat.POSSESSIVE,
            atomWasGroup);
        return mode == Repeat.POSSESSIVE ? new GroupNodes.Atomic(loop, false) : loop;
    }

    /**
     * A capturing group written directly under the quantifier (not wrapped in (?:..)) whose body
     * can only match the empty string, with no loops or branches.
     */
    private boolean skipsEmptyCapture(Node atom) {
        return atomCapturing && atom instanceof GroupNodes.Group
            && Repeat.isFixedEmpty(((GroupNodes.Group) atom).body());
    }
}
