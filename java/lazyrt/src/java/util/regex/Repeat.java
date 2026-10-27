package java.util.regex;

/**
 * Quantifiers. A body that always consumes exactly one code point of a class
 * gets {@link CharRepeat}, which counts forward in a loop and backs off without
 * recursion, so `.*` over a long text uses constant stack. Any other body gets
 * {@link Loop}, which recurses once per iteration.
 */
final class Repeat {
    static final int GREEDY = 0, LAZY = 1, POSSESSIVE = 2;

    private Repeat() {
    }

    /** x{min,max} for a single-code-point class x. */
    static final class CharRepeat extends Node {
        private final CharClass cc;
        private final int minCount;
        private final int maxCount;
        private final int mode;

        CharRepeat(CharClass cc, int minCount, int maxCount, int mode) {
            this.cc = cc;
            this.minCount = minCount;
            this.maxCount = maxCount;
            this.mode = mode;
        }

        int minCount() {
            return minCount;
        }

        int maxCount() {
            return maxCount;
        }

        /** Width of the code point matched at j, or 0 if there is none that is in the class. */
        private int widthAt(Matcher m, int j) {
            if (j >= m.hi) {
                m.hitEnd = true;
                return 0;
            }
            int c = Atoms.codePointAt(m, j);
            return cc.test(c) ? Character.charCount(c) : 0;
        }

        boolean match(Matcher m, int i) {
            if (mode == LAZY) {
                return lazy(m, i);
            }
            int j = i;
            int n = 0;
            while (n < maxCount) {
                int w = widthAt(m, j);
                if (w == 0) {
                    break;
                }
                j += w;
                n++;
            }
            if (n < minCount) {
                return false;
            }
            if (mode == POSSESSIVE) {
                return next.match(m, j);
            }
            while (true) {
                if (next.match(m, j)) {
                    return true;
                }
                if (n == minCount) {
                    return false;
                }
                n--;
                j -= j - 2 >= i && Character.isLowSurrogate(m.text.charAt(j - 1))
                    && Character.isHighSurrogate(m.text.charAt(j - 2)) ? 2 : 1;
            }
        }

        private boolean lazy(Matcher m, int i) {
            int j = i;
            int n = 0;
            while (n < minCount) {
                int w = widthAt(m, j);
                if (w == 0) {
                    return false;
                }
                j += w;
                n++;
            }
            while (true) {
                if (next.match(m, j)) {
                    return true;
                }
                if (n >= maxCount) {
                    return false;
                }
                int w = widthAt(m, j);
                if (w == 0) {
                    return false;
                }
                j += w;
                n++;
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
        /** True for a (possibly lazy) group repeated by * + or {n,m}: Lengths gives up there. */
        final boolean grouped;
        private Tail tail;

        Loop(Node body, int minCount, int maxCount, boolean lazy, int id, boolean grouped) {
            this.grouped = grouped;
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
            this.tail = new Tail(id, minCount, maxCount, lazy);
            tail.next = after;
            tail.head = body.link(tail);
            return this;
        }

        boolean match(Matcher m, int i) {
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
}
