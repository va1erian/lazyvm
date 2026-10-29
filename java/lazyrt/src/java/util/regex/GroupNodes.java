package java.util.regex;

/**
 * Capturing groups, lookaround and atomic groups. Captures made inside a
 * lookaround body or an atomic group stay set even if the match later backs
 * out of it, so a failed branch can leave a group set. Observed on the oracle
 * (RegexGroups, last block).
 */
final class GroupNodes {
    private GroupNodes() {
    }

    /** `( ... )`: records the span of its body as group n. This node is the opening half. */
    static final class Group extends Node {
        private final int n;
        private final Node body;

        Group(int n, Node body) {
            this.n = n;
            this.body = body;
        }

        Node body() {
            return body;
        }

        int number() {
            return n;
        }

        int go(Matcher m, int i) {
            int at = m.stack.push(this);
            m.stack.data[at] = m.open[n];
            m.open[n] = i;
            m.cont = next;
            return i;
        }

        int back(Matcher m, int at) {
            m.open[n] = m.stack.data[at];
            return FAIL;
        }

        Node link(Node after) {
            Close close = new Close(n);
            close.next = after;
            this.next = body.link(close);
            return this;
        }

        int min() {
            return body.min();
        }

        boolean blocksMin() {
            return body.blocksMin();
        }

        int max() {
            return body.max();
        }
    }

    private static final class Close extends Node {
        private final int n;

        Close(int n) {
            this.n = n;
        }

        int go(Matcher m, int i) {
            int at = m.stack.push(this);
            int[] d = m.stack.data;
            d[at] = m.groups[2 * n];
            d[at + 1] = m.groups[2 * n + 1];
            m.groups[2 * n] = m.open[n];
            m.groups[2 * n + 1] = i;
            m.cont = next;
            return i;
        }

        int back(Matcher m, int at) {
            int[] d = m.stack.data;
            m.groups[2 * n] = d[at];
            m.groups[2 * n + 1] = d[at + 1];
            return FAIL;
        }
    }

    /** (?=x) (?!x) (?<=x) (?<!x). A lookbehind body may have any width; every start that could end here is tried. */
    static final class Look extends Node.Scope {
        private final Node body;
        private final boolean ahead;
        private final boolean positive;
        private final int minLen;
        private final int maxLen;
        /**
         * Observed on the oracle (RegexLookbehindPairs): when the pattern text has a surrogate char
         * written raw at or after the start of the lookbehind, the starts tried are found by
         * stepping back whole code points, minLen to maxLen of them, so a start never falls between
         * the halves of a pair. Otherwise the way back is counted in chars.
         */
        private final boolean byCodePoints;
        /** A class the body must begin with, or null (see SearchHints). */
        private final CharClass firstChars;
        private final int id;
        private Node head;

        /** `maxLen` is how far back a lookbehind may look (see LookbehindReach). */
        Look(Node body, boolean ahead, boolean positive, int maxLen, int id, boolean byCodePoints) {
            this.id = id;
            this.byCodePoints = byCodePoints;
            this.body = body;
            this.ahead = ahead;
            this.positive = positive;
            this.minLen = body.min();
            this.maxLen = maxLen;
            this.firstChars = SearchHints.mustStartWith(body);
        }

        Node body() {
            return body;
        }

        Node link(Node after) {
            this.next = after;
            this.head = body.link(ahead ? (Node) new Stop() : new EndAt());
            return this;
        }

        // A scope frame holds the bound to restore (hi for a lookahead, lo for a lookbehind), the
        // behindTarget to restore, the position the lookaround was entered at, and for a
        // lookbehind the start being tried, the code points stepped back to reach it, and the
        // lowest start (in code-point mode the floor of a wrapped reach).
        private static final int SAVED = 0, TARGET = 1, AT = 2, START = 3, STEPS = 4, LOWEST = 5;

        int go(Matcher m, int i) {
            if (!ahead) {
                return lookBehind(m, i);
            }
            // Observed on the oracle (RegexRegions, "negative lookahead at the end"): a negative
            // lookahead entered at the end of the text sets requireEnd, and keeps it if the match
            // later fails; with transparent bounds the end is the end of the text.
            if (!positive && i >= (m.transparentBounds ? m.textLength : m.hi)) {
                m.requireEnd = true;
            }
            int at = m.stack.pushScope(this);
            int[] d = m.stack.data;
            d[at + SAVED] = m.hi;
            d[at + AT] = i;
            if (m.transparentBounds) {
                m.hi = m.textLength;
            }
            m.cont = head;
            return i;
        }

        /** Goes on after the lookaround at i, given whether its body matched. */
        private int outcome(Matcher m, boolean found, int i) {
            if (found != positive) {
                return FAIL;
            }
            m.cont = next;
            return i;
        }

        int matched(Matcher m, int at) {
            int[] d = m.stack.data;
            if (ahead) {
                m.hi = d[at + SAVED];
                // Observed on the oracle (RegexAfterFailure): where a lookahead body ended, positive
                // or negative, is the last event of the attempt, like the end of an atomic body.
                m.iterationEnd = m.stopPos;
            } else {
                m.lo = d[at + SAVED];
                m.behindTarget = d[at + TARGET];
            }
            return outcome(m, true, d[at + AT]);
        }

        /** A lookbehind tries the next start. */
        int failed(Matcher m, int at) {
            int[] d = m.stack.data;
            int i = d[at + AT];
            if (ahead) {
                m.hi = d[at + SAVED];
                return outcome(m, false, i);
            }
            int lo = d[at + SAVED];
            int target = d[at + TARGET];
            int start = d[at + START];
            int lowest = d[at + LOWEST];
            if (byCodePoints) {
                return nextByCodePoints(m, lo, target, i, start, d[at + STEPS], lowest);
            }
            return tryFrom(m, lo, target, i, start - 1, lowest);
        }

        private int lookBehind(Matcher m, int i) {
            int lo = m.lo;
            int target = m.behindTarget;
            if (m.transparentBounds) {
                m.lo = 0;
            }
            m.behindTarget = i;
            if (byCodePoints) {
                return behindByCodePoints(m, lo, target, i);
            }
            int lowest = i - maxLen;
            if (lowest < m.lo) {
                lowest = m.lo;
            }
            // Over a long way back, a bit set of the possible starts lets the search jump between them.
            if (firstChars != null && i - lowest > 2048 && m.starts[id] == null) {
                m.starts[id] = new StartIndex(m, firstChars);
            }
            return tryFrom(m, lo, target, i, i - minLen, lowest);
        }

        /**
         * Runs the body from the highest start at or below `start`, down to `lowest`, that the body
         * can begin at: a start it cannot begin at fails without any effect, so it is not tried.
         */
        private int tryFrom(Matcher m, int lo, int target, int i, int start, int lowest) {
            StartIndex index = firstChars != null && i - lowest > 2048 ? m.starts[id] : null;
            for (; start >= lowest; start--) {
                if (index != null) {
                    start = index.previous(start, lowest);
                    if (start < lowest) {
                        break;
                    }
                }
                if (firstChars == null || firstChars.test(Atoms.codePointAt(m, start))) {
                    return tryAt(m, lo, target, i, start, 0, lowest);
                }
            }
            return exhausted(m, lo, target, i);
        }

        /** Runs the body from `start`, in a scope that returns to this lookbehind. */
        private int tryAt(Matcher m, int lo, int target, int i, int start, int steps, int lowest) {
            int at = m.stack.pushScope(this);
            int[] d = m.stack.data;
            d[at + SAVED] = lo;
            d[at + TARGET] = target;
            d[at + AT] = i;
            d[at + START] = start;
            d[at + STEPS] = steps;
            d[at + LOWEST] = lowest;
            m.cont = head;
            return start;
        }

        /** No start is left: the body did not match. */
        private int exhausted(Matcher m, int lo, int target, int i) {
            m.lo = lo;
            m.behindTarget = target;
            return outcome(m, false, i);
        }

        /**
         * Observed on the oracle (RegexLookbehindEdges): starts are tried by stepping back one code
         * point at a time over the text, not the region, from minLen of them (or the start of the
         * text when fewer lie behind) up to maxLen of them. A start before the region start is not
         * tried, and a step that reads a pair the region start splits ends the search. A reach that
         * wrapped negative is a bound in chars instead (see wrappedFloor).
         */
        private int behindByCodePoints(Matcher m, int lo, int target, int i) {
            int floor = maxLen >= 0 ? Integer.MIN_VALUE : wrappedFloor(m, i, -maxLen);
            int start = i;
            int n = 0;
            while (n < minLen && start > 0) {
                start = stepBack(m, start);
                n++;
            }
            if (start < m.lo) {
                return exhausted(m, lo, target, i);
            }
            if (n <= stepLimit() && start >= floor) {
                return tryAt(m, lo, target, i, start, n, floor);
            }
            return nextByCodePoints(m, lo, target, i, start, n, floor);
        }

        /** Tries the start one code point before `start`, n steps back, when it is in reach. */
        private int nextByCodePoints(Matcher m, int lo, int target, int i, int start, int n, int floor) {
            if (n < stepLimit() && start > m.lo) {
                start = stepBack(m, start);
                if (start >= m.lo && start >= floor) {
                    return tryAt(m, lo, target, i, start, n + 1, floor);
                }
            }
            return exhausted(m, lo, target, i);
        }

        private int stepLimit() {
            return maxLen >= 0 ? maxLen : Integer.MAX_VALUE;
        }

        /**
         * Observed on the oracle (RegexLookbehindEdges, wrappedFloor): for a reach that wrapped to
         * -k, the lowest start lies as many chars before i as k code points take after i, the walk
         * stopping at the end of the text (not of the region). A pair after i thus lets the search
         * go one char further back, and at the end of the text only i itself is left, as it is for
         * Integer.MIN_VALUE, whose negation overflows and walks nothing.
         */
        private static int wrappedFloor(Matcher m, int i, int k) {
            int f = i;
            for (int c = 0; c < k && f < m.textLength; c++) {
                boolean pair = f + 1 < m.textLength && Character.isSurrogatePair(m.text.charAt(f), m.text.charAt(f + 1));
                f += pair ? 2 : 1;
            }
            return i - (f - i);
        }

        private static int stepBack(Matcher m, int start) {
            boolean pair = start >= 2 && Character.isSurrogatePair(m.text.charAt(start - 2), m.text.charAt(start - 1));
            return start - (pair ? 2 : 1);
        }

        int min() {
            return 0;
        }
    }

    /** (?>x): matches x once and never backtracks into it. */
    static final class Atomic extends Node.Scope {
        private final Node body;
        private final boolean group;
        private Node head;

        /** `group` is true for a written (?>..), false for the wrapper of a possessive repeat. */
        Atomic(Node body, boolean group) {
            this.body = body;
            this.group = group;
        }

        Node body() {
            return body;
        }

        /** True for a written (?>..), false for a possessive repeat's wrapper. */
        boolean written() {
            return group;
        }

        Node link(Node after) {
            this.next = after;
            this.head = body.link(new Stop());
            return this;
        }

        int go(Matcher m, int i) {
            m.stack.pushScope(this);
            m.cont = head;
            return i;
        }

        int matched(Matcher m, int at) {
            if (group) {
                // Observed on the oracle (RegexAfterFailure): when the body of a written (?>..)
                // matches, what it records is where it ended, replacing the runs and iterations
                // inside it; when it fails those stay.
                m.iterationEnd = m.stopPos;
            }
            m.cont = next;
            return m.stopPos;
        }

        int failed(Matcher m, int at) {
            return FAIL;
        }

        int min() {
            return body.min();
        }

        // Observed on the oracle (RegexEnds): an atomic group hides a block inside it, so what
        // follows the group still counts towards the shortest match.
        boolean blocksMin() {
            return false;
        }

        int max() {
            return body.max();
        }
    }
}
