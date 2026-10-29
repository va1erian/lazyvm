package java.util.regex;

/**
 * Quantifiers. A body that always consumes exactly one code point of a class
 * (also inside one capturing group) gets {@link CharRepeat}, which counts
 * forward in a loop and backs off one char at a time from a single frame, so
 * `.*` and `(\d)+` take no stack per char. A body made only of such pieces,
 * literals and zero-width anchors, possibly with alternatives, runs in
 * {@link FlatLoop}, which keeps its backtracking states in an array. Any other
 * {@link Loop} runs its body between {@link LoopTail} decisions, with a frame on
 * the matcher's stack (Backtrack) for each decision.
 */
final class Repeat {
    static final int GREEDY = 0, LAZY = 1, POSSESSIVE = 2;

    private Repeat() {
    }

    /** Width of the code point at j if it is in cc, else 0; reaching the end sets hitEnd. */
    static int widthAt(Matcher m, CharClass cc, int j) {
        if (j >= m.hi) {
            m.hitEnd = true;
            return 0;
        }
        // Inline for a char that does not start a pair, as in Atoms.One.
        int c = m.text.charAt(j);
        if (c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE) {
            c = Atoms.codePointAt(m, j);
        }
        return !cc.test(c) ? 0 : c >= Character.MIN_SUPPLEMENTARY_CODE_POINT ? 2 : 1;
    }

    /** Width of the char ending at j when the scan started at i: 2 for a whole surrogate pair. */
    static int widthBefore(Matcher m, int i, int j) {
        return j - 2 >= i && Character.isLowSurrogate(m.text.charAt(j - 1))
            && Character.isHighSurrogate(m.text.charAt(j - 2)) ? 2 : 1;
    }

    /**
     * x{min,max} for a single-code-point class x, optionally inside a capturing group
     * `(x){min,max}` whose capture is the last char matched.
     */
    static final class CharRepeat extends Node {
        final CharClass cc;
        private final int minCount;
        private final int maxCount;
        final int mode;
        /** Capturing group number, or -1. */
        final int group;
        /** The class came from folding an alternation, as in (?:a|b)*. */
        final boolean fromAlternation;
        /** The quantifier was written '?'. */
        final boolean question;

        /**
         * Observed on the oracle (RegexAfterFailure): the end of a run of a repeated class is an
         * event for Matcher.iterationEnd, except for a class made of alternatives (?:a|b)*, which
         * the oracle runs as a loop and which records nothing unless it is possessive.
         */
        private final boolean records;

        boolean recordsRuns() {
            return records;
        }

        /** A possessive repeat of a group leaves its capture set when the match later backs out. */
        boolean keepsCapture() {
            return group >= 0 && mode == POSSESSIVE;
        }
        /** The class was written inside a (..) or (?:..) group. */
        final boolean grouped;
        /** The class the rest of the pattern must begin with, or null (see leadingClass). */
        private CharClass follow;

        CharRepeat(CharClass cc, int minCount, int maxCount, int mode, int group, boolean fromAlternation,
                   boolean question, boolean grouped) {
            this.grouped = grouped;
            this.cc = cc;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.mode = mode;
            this.group = group;
            this.fromAlternation = fromAlternation;
            this.question = question;
            this.records = !fromAlternation || mode == POSSESSIVE;
        }

        int minCount() {
            return minCount;
        }

        int maxCount() {
            return maxCount;
        }

        /**
         * Enumerates the ways to match at p for FlatLoop: the first call (retry false) returns the
         * end of the preferred way and records the char count in st[countAt]; each later call
         * returns the end of the next way, given the previous one's end, or -1.
         */
        int nextWay(Matcher m, int p, int[] st, int countAt, int prevEnd, boolean retry) {
            if (!retry) {
                int j = p;
                int n = 0;
                int limit = mode == LAZY ? minCount : maxCount;
                while (n < limit) {
                    int w = widthAt(m, cc, j);
                    if (w == 0) {
                        break;
                    }
                    j += w;
                    n++;
                }
                st[countAt] = n;
                if (n > 0 && records) {
                    m.iterationEnd = j;
                }
                return n < minCount ? -1 : j;
            }
            int n = st[countAt];
            if (mode == POSSESSIVE) {
                return -1;
            }
            if (mode == LAZY) {
                int w = n >= maxCount ? 0 : widthAt(m, cc, prevEnd);
                if (w == 0) {
                    return -1;
                }
                st[countAt] = n + 1;
                if (records) {
                    m.iterationEnd = prevEnd + w;
                }
                return prevEnd + w;
            }
            if (n == minCount) {
                return -1;
            }
            st[countAt] = n - 1;
            return prevEnd - widthBefore(m, p, prevEnd);
        }

        /**
         * Goes on at j after n chars, with the group (if any) set to the last one. A frame is left
         * when the repeat has another count to try, or a capture to undo: a possessive repeat
         * does neither (observed on the oracle, RegexGroups, "possessive group keeps its capture").
         */
        private int proceed(Matcher m, int i, int j, int n) {
            // A count after which the rest cannot begin is passed over: trying it would fail at
            // once without any effect, because j is inside the bounds, its char is not a surrogate
            // (a pair read whole could reach past the end and set hitEnd), and it is outside the
            // class the rest must begin with. A lazy repeat still records each char it takes.
            CharClass f = follow;
            while (f != null && j < m.hi) {
                char c = m.text.charAt(j);
                if ((c >= Character.MIN_SURROGATE && c <= Character.MAX_SURROGATE) || f.test(c)) {
                    break;
                }
                if (mode == GREEDY && n > minCount) {
                    n--;
                    j -= widthBefore(m, i, j);
                    continue;
                }
                int w = mode == LAZY && n < maxCount ? widthAt(m, cc, j) : 0;
                if (w == 0) {
                    return FAIL;
                }
                j += w;
                n++;
                if (records) {
                    m.iterationEnd = j;
                }
            }
            boolean capture = group >= 0 && n > 0;
            boolean more = mode == GREEDY ? n > minCount : mode == LAZY && n < maxCount;
            if (more || (capture && mode != POSSESSIVE)) {
                int at = m.stack.push(this);
                int[] d = m.stack.data;
                d[at] = i;
                d[at + 1] = j;
                d[at + 2] = n;
                if (capture) {
                    d[at + 3] = m.groups[2 * group];
                    d[at + 4] = m.groups[2 * group + 1];
                }
            }
            if (capture) {
                m.groups[2 * group] = j - widthBefore(m, i, j);
                m.groups[2 * group + 1] = j;
            }
            m.cont = next;
            return j;
        }

        Node link(Node after) {
            this.next = after;
            // A possessive repeat that captures keeps its capture when the rest fails, so every
            // try of the rest has an effect.
            this.follow = keepsCapture() ? null : leadingClass(after);
            return this;
        }

        /**
         * The class a linked node must begin with when it fails at once, without any effect, at a
         * char outside it: one class, or a literal run's first char, possibly entered through the
         * opening halves of groups (which undo what they set when they fail). Else null.
         */
        private static CharClass leadingClass(Node n) {
            while (n instanceof GroupNodes.Group) {
                n = n.next;
            }
            if (n instanceof Atoms.One) {
                return ((Atoms.One) n).cc;
            }
            if (n instanceof Atoms.Str && !Character.isSurrogate(((Atoms.Str) n).chars[0])) {
                return CharClass.single(((Atoms.Str) n).chars[0]);
            }
            return null;
        }

        /** The rest of the pattern failed after n chars: undo the capture and try the next count. */
        int back(Matcher m, int at) {
            int[] d = m.stack.data;
            int i = d[at];
            int j = d[at + 1];
            int n = d[at + 2];
            if (group >= 0 && n > 0) {
                m.groups[2 * group] = d[at + 3];
                m.groups[2 * group + 1] = d[at + 4];
            }
            if (mode == LAZY) {
                int w = n >= maxCount ? 0 : widthAt(m, cc, j);
                if (w == 0) {
                    return FAIL;
                }
                j += w;
                if (records) {
                    m.iterationEnd = j;
                }
                return proceed(m, i, j, n + 1);
            }
            if (n == minCount) {
                return FAIL;
            }
            return proceed(m, i, j - widthBefore(m, i, j), n - 1);
        }

        int go(Matcher m, int i) {
            int j = i;
            int n = 0;
            if (mode == LAZY) {
                while (n < minCount) {
                    int w = widthAt(m, cc, j);
                    if (w == 0) {
                        return FAIL;
                    }
                    j += w;
                    n++;
                    if (records) {
                        m.iterationEnd = j;
                    }
                }
                return proceed(m, i, j, n);
            }
            while (n < maxCount) {
                int w = widthAt(m, cc, j);
                if (w == 0) {
                    break;
                }
                j += w;
                n++;
            }
            if (n > 0 && records) {
                m.iterationEnd = j;
            }
            return n < minCount ? FAIL : proceed(m, i, j, n);
        }

        int min() {
            // Observed on the oracle: a repeat of an alternation counts nothing towards the
            // shortest match, which decides where a failed search stops and so its hitEnd and
            // requireEnd ((?:B|C)+z on "" reports requireEnd). Pinned by RegexEnds, blessed in
            // RegexEnds.out; mutation: count minCount again.
            return blocksMin() ? 0 : mul(1, minCount);
        }

        boolean blocksMin() {
            return fromAlternation && mode != POSSESSIVE && !question;
        }

        int max() {
            return mul(2, maxCount);
        }
    }

    /**
     * x{min,max} for any x. The iteration count and the start of the current
     * iteration live in the matcher (slot `id`), so one node can be re-entered
     * by an enclosing loop. An iteration that consumed nothing ends the loop,
     * which keeps `(a*)*` from spinning.
     */
    static final class Loop extends Node {
        private final Node body;
        private final int minCount;
        private final int maxCount;
        private final boolean lazy;
        private final int id;
        /** True for a group repeated by * + or {n,m} (see LookbehindReach). */
        final boolean grouped;
        /** The quantifier was written '?'. */
        final boolean question;
        /** The quantifier was possessive (the loop sits inside an atomic wrapper). */
        final boolean possessive;
        /** The repeated atom was written as a (..) or (?:..) group. */
        final boolean inGroup;
        private LoopTail tail;
        private FlatLoop flat;

        /** Lets this loop remember failed states (see LoopMemo). */
        void memoize(boolean on) {
            memoizable = on;
        }

        private boolean memoizable;

        /**
         * Observed on the oracle (RegexRegions, RegexAfterFailure): after a failed attempt the
         * "end of the previous match" is the end of the last greedy or lazy run of a repeated
         * class and of the last iteration of a loop over a fixed-width body, in the order they
         * happened; backing off does not move it. A loop written '?', or over a body with
         * alternatives or a variable part, records no iterations of its own unless it is
         * possessive.
         */
        private boolean recordsIterations() {
            return possessive || (!question && LookbehindReach.fixedShape(body, false));
        }

        Loop(Node body, int minCount, int maxCount, boolean lazy, int id, boolean grouped, boolean question,
             boolean possessive, boolean inGroup) {
            this.inGroup = inGroup;
            this.grouped = grouped;
            this.question = question;
            this.possessive = possessive;
            this.body = body;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.lazy = lazy;
            this.id = id;
        }

        Node body() {
            return body;
        }

        int minCount() {
            return minCount;
        }

        int maxCount() {
            return maxCount;
        }

        Node link(Node after) {
            this.next = after;
            this.flat = FlatLoop.of(body, minCount, maxCount, lazy, id);
            if (flat != null) {
                flat.next = after;
                flat.memoizable = memoizable;
                flat.records = recordsIterations();
                return this;
            }
            this.tail = new LoopTail(id, minCount, maxCount, lazy);
            tail.next = after;
            tail.memoizable = memoizable;
            tail.records = recordsIterations();
            tail.head = body.link(tail);
            return this;
        }

        int go(Matcher m, int i) {
            if (flat != null) {
                return flat.go(m, i);
            }
            int at = m.stack.push(this);
            int[] d = m.stack.data;
            d[at] = m.loopCount[id];
            d[at + 1] = m.loopStart[id];
            m.loopCount[id] = 0;
            m.loopStart[id] = -1;
            return tail.go(m, i);
        }

        /** The loop failed: an enclosing loop may enter it again, so its counters come back. */
        int back(Matcher m, int at) {
            int[] d = m.stack.data;
            m.loopCount[id] = d[at];
            m.loopStart[id] = d[at + 1];
            return FAIL;
        }

        int min() {
            // Observed on the oracle: as for CharRepeat, a repeat of a body with alternatives or
            // a variable part counts nothing; a possessive repeat and an atomic body count fully.
            return blocksMin() ? 0 : mul(body.min(), minCount);
        }

        boolean blocksMin() {
            return !question && !possessive && !LookbehindReach.fixedShape(body, false);
        }

        int max() {
            return mul(body.max(), maxCount);
        }
    }

    /** True when n can only match the empty string, with no loops over anything wider or branches. */
    static boolean isFixedEmpty(Node n) {
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
