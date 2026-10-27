package java.util.regex;

/**
 * Quantifiers. A body that always consumes exactly one code point of a class
 * (also inside one capturing group) gets {@link CharRepeat}, which counts
 * forward in a loop and backs off without recursion, so `.*` and `(\d)+` use
 * constant stack. A body made only of such pieces, literals and zero-width
 * anchors, possibly with alternatives, runs in {@link FlatLoop}, which keeps
 * its backtracking states in an array. Any other {@link Loop} recurses, about
 * four Java frames per iteration.
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
        int c = Atoms.codePointAt(m, j);
        return cc.test(c) ? Character.charCount(c) : 0;
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
        /** The class was written inside a (..) or (?:..) group. */
        final boolean grouped;

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
                return prevEnd + w;
            }
            if (n == minCount) {
                return -1;
            }
            st[countAt] = n - 1;
            return prevEnd - widthBefore(m, p, prevEnd);
        }

        /** Continues at j after n chars, with the group (if any) set to the last one. */
        private boolean proceed(Matcher m, int i, int j, int n) {
            if (group < 0 || n == 0) {
                return next.match(m, j);
            }
            int oldStart = m.groups[2 * group];
            int oldEnd = m.groups[2 * group + 1];
            m.groups[2 * group] = j - widthBefore(m, i, j);
            m.groups[2 * group + 1] = j;
            if (next.match(m, j)) {
                return true;
            }
            if (mode == POSSESSIVE) {
                // Observed on the oracle (RegexGroups, "possessive group keeps its capture"): a
                // possessive repeat does not undo its capture when the rest of the match fails.
                return false;
            }
            m.groups[2 * group] = oldStart;
            m.groups[2 * group + 1] = oldEnd;
            return false;
        }

        boolean match(Matcher m, int i) {
            if (mode == LAZY) {
                return lazy(m, i);
            }
            int j = i;
            int n = 0;
            while (n < maxCount) {
                int w = widthAt(m, cc, j);
                if (w == 0) {
                    break;
                }
                j += w;
                n++;
            }
            if (n > 0) {
                m.iterationEnd = j;
            }
            if (n < minCount) {
                return false;
            }
            if (mode == POSSESSIVE) {
                return proceed(m, i, j, n);
            }
            while (true) {
                if (proceed(m, i, j, n)) {
                    return true;
                }
                if (n == minCount) {
                    return false;
                }
                n--;
                j -= widthBefore(m, i, j);
            }
        }

        private boolean lazy(Matcher m, int i) {
            int j = i;
            int n = 0;
            while (n < minCount) {
                int w = widthAt(m, cc, j);
                if (w == 0) {
                    return false;
                }
                j += w;
                n++;
                m.iterationEnd = j;
            }
            while (true) {
                if (proceed(m, i, j, n)) {
                    return true;
                }
                if (n >= maxCount) {
                    return false;
                }
                int w = widthAt(m, cc, j);
                if (w == 0) {
                    return false;
                }
                j += w;
                n++;
                m.iterationEnd = j;
            }
        }

        int min() {
            return mul(1, minCount);
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
        private Tail tail;
        private FlatLoop flat;

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
            this.flat = FlatLoop.of(body, minCount, maxCount, lazy);
            if (flat != null) {
                flat.next = after;
                return this;
            }
            this.tail = new Tail(id, minCount, maxCount, lazy);
            tail.next = after;
            tail.head = body.link(tail);
            return this;
        }

        boolean match(Matcher m, int i) {
            if (flat != null) {
                return flat.match(m, i);
            }
            int savedCount = m.loopCount[id];
            int savedStart = m.loopStart[id];
            m.loopCount[id] = 0;
            m.loopStart[id] = -1;
            if (tail.match(m, i)) {
                return true;
            }
            m.loopCount[id] = savedCount;
            m.loopStart[id] = savedStart;
            return false;
        }

        int min() {
            return mul(body.min(), minCount);
        }

        int max() {
            return mul(body.max(), maxCount);
        }
    }

    /**
     * The end of one iteration of a Loop body, where the loop decides to run the
     * body again or leave. It starts the next iteration itself rather than
     * through helper methods, because each Java frame saved here is one more
     * iteration a pattern like (a|b)* can run before the frame limit.
     */
    private static final class Tail extends Node {
        private final int id;
        private final int minCount;
        private final int maxCount;
        private final boolean lazy;
        Node head;

        Tail(int id, int minCount, int maxCount, boolean lazy) {
            this.id = id;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.lazy = lazy;
        }

        boolean match(Matcher m, int i) {
            int c = m.loopCount[id];
            if (c > 0 && i == m.loopStart[id]) {
                // The last iteration consumed nothing, so more would add nothing; the minimum
                // counts as met; observed on the oracle with (\n*?){2}a, see RegexQuantifiers).
                return next.match(m, i);
            }
            if (c > 0) {
                m.iterationEnd = i;
            }
            boolean required = c < minCount;
            if (lazy && !required && next.match(m, i)) {
                return true;
            }
            if (required || c < maxCount) {
                int savedStart = m.loopStart[id];
                m.loopCount[id] = c + 1;
                m.loopStart[id] = i;
                if (head.match(m, i)) {
                    return true;
                }
                m.loopCount[id] = c;
                m.loopStart[id] = savedStart;
            }
            return !required && !lazy && next.match(m, i);
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
