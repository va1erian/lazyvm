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

        boolean match(Matcher m, int i) {
            int old = m.open[n];
            m.open[n] = i;
            if (next.match(m, i)) {
                return true;
            }
            m.open[n] = old;
            return false;
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

        boolean match(Matcher m, int i) {
            int oldStart = m.groups[2 * n];
            int oldEnd = m.groups[2 * n + 1];
            m.groups[2 * n] = m.open[n];
            m.groups[2 * n + 1] = i;
            if (next.match(m, i)) {
                return true;
            }
            m.groups[2 * n] = oldStart;
            m.groups[2 * n + 1] = oldEnd;
            return false;
        }
    }

    /** (?=x) (?!x) (?<=x) (?<!x). A lookbehind body may have any width; every start that could end here is tried. */
    static final class Look extends Node {
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

        boolean match(Matcher m, int i) {
            boolean found = ahead ? lookAhead(m, i) : lookBehind(m, i);
            return found == positive && next.match(m, i);
        }

        private boolean lookAhead(Matcher m, int i) {
            int hi = m.hi;
            // Observed on the oracle (RegexRegions, "negative lookahead at the end"): a negative
            // lookahead entered at the end of the text sets requireEnd, and keeps it if the match
            // later fails; with transparent bounds the end is the end of the text.
            if (!positive && i >= (m.transparentBounds ? m.textLength : m.hi)) {
                m.requireEnd = true;
            }
            if (m.transparentBounds) {
                m.hi = m.textLength;
            }
            boolean found = head.match(m, i);
            m.hi = hi;
            if (found) {
                // Observed on the oracle (RegexAfterFailure): where a lookahead body ended, positive
                // or negative, is the last event of the attempt, like the end of an atomic body.
                m.iterationEnd = m.stopPos;
            }
            return found;
        }

        private boolean lookBehind(Matcher m, int i) {
            int lo = m.lo;
            int target = m.behindTarget;
            if (m.transparentBounds) {
                m.lo = 0;
            }
            m.behindTarget = i;
            if (byCodePoints) {
                boolean found = behindByCodePoints(m, i);
                m.lo = lo;
                m.behindTarget = target;
                return found;
            }
            int lowest = i - maxLen;
            if (lowest < m.lo) {
                lowest = m.lo;
            }
            boolean found = false;
            // A start the body cannot begin at fails without any effect, so it is not tried; over a
            // long way back a bit set of the possible starts lets the loop jump between them.
            StartIndex index = null;
            if (firstChars != null && i - lowest > 2048) {
                if (m.starts[id] == null) {
                    m.starts[id] = new StartIndex(m, firstChars);
                }
                index = m.starts[id];
            }
            for (int start = i - minLen; start >= lowest && !found; start--) {
                if (index != null) {
                    start = index.previous(start, lowest);
                    if (start < lowest) {
                        break;
                    }
                }
                if (firstChars == null || firstChars.test(Atoms.codePointAt(m, start))) {
                    found = head.match(m, start);
                }
            }
            m.lo = lo;
            m.behindTarget = target;
            return found;
        }

        /**
         * Observed on the oracle (RegexLookbehindEdges): starts are tried by stepping back one code
         * point at a time over the text, not the region, from minLen of them (or the start of the
         * text when fewer lie behind) up to maxLen of them. A start before the region start is not
         * tried (lowestStart), and a step that reads a pair the region start splits ends the
         * search. A reach that wrapped negative is a bound in chars instead (see wrappedFloor).
         */
        private boolean behindByCodePoints(Matcher m, int i) {
            int limit = maxLen >= 0 ? maxLen : Integer.MAX_VALUE;
            int floor = maxLen >= 0 ? Integer.MIN_VALUE : wrappedFloor(m, i, -maxLen);
            int start = i;
            int n = 0;
            while (n < minLen && start > 0) {
                start = stepBack(m, start);
                n++;
            }
            if (start < m.lo) {
                return false;
            }
            if (n <= limit && start >= floor && head.match(m, start)) {
                return true;
            }
            while (n < limit && start > m.lo) {
                start = stepBack(m, start);
                n++;
                if (start < m.lo || start < floor) {
                    return false;
                }
                if (head.match(m, start)) {
                    return true;
                }
            }
            return false;
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
    static final class Atomic extends Node {
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

        boolean match(Matcher m, int i) {
            if (!group) {
                return head.match(m, i) && next.match(m, m.stopPos);
            }
            // Observed on the oracle (RegexAfterFailure): when the body of a written (?>..)
            // matches, what it records is where it ended, replacing the runs and iterations
            // inside it; when it fails those stay.
            if (!head.match(m, i)) {
                return false;
            }
            m.iterationEnd = m.stopPos;
            return next.match(m, m.stopPos);
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
