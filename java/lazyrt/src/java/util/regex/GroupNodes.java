package java.util.regex;

/**
 * Capturing groups, lookaround and atomic groups. Captures made inside a
 * lookaround body or an atomic group stay set even if the match later backs
 * out of it; the reference implementation behaves the same, so a failed
 * branch can leave a group set.
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
        private Node head;

        Look(Node body, boolean ahead, boolean positive) {
            this.body = body;
            this.ahead = ahead;
            this.positive = positive;
            this.minLen = body.min();
            this.maxLen = body.max();
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
            if (!positive && i >= m.hi) {
                m.requireEnd = true;
            }
            if (m.transparentBounds) {
                m.hi = m.textLength;
            }
            boolean found = head.match(m, i);
            m.hi = hi;
            return found;
        }

        private boolean lookBehind(Matcher m, int i) {
            int lo = m.lo;
            int target = m.behindTarget;
            if (m.transparentBounds) {
                m.lo = 0;
            }
            m.behindTarget = i;
            int lowest = maxLen >= i ? 0 : i - maxLen;
            if (lowest < m.lo) {
                lowest = m.lo;
            }
            boolean found = false;
            for (int start = i - minLen; start >= lowest && !found; start--) {
                found = head.match(m, start);
            }
            m.lo = lo;
            m.behindTarget = target;
            return found;
        }

        int min() {
            return 0;
        }
    }

    /** (?>x): matches x once and never backtracks into it. */
    static final class Atomic extends Node {
        private final Node body;
        private Node head;

        Atomic(Node body) {
            this.body = body;
        }

        Node body() {
            return body;
        }

        Node link(Node after) {
            this.next = after;
            this.head = body.link(new Stop());
            return this;
        }

        boolean match(Matcher m, int i) {
            return head.match(m, i) && next.match(m, m.stopPos);
        }

        int min() {
            return body.min();
        }

        int max() {
            return body.max();
        }
    }
}
