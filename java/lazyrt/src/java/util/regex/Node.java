package java.util.regex;

/**
 * A node of the compiled pattern. The parser builds a tree, then {@link #link}
 * wires every node to the node that must match after it, so matching is a walk
 * through `next` pointers: a node consumes input and calls `next.match`, and a
 * false return means "backtrack". Nodes that change matcher state restore it
 * when they return false; a true return leaves the state as the successful
 * match needs it.
 */
abstract class Node {
    static final int UNBOUNDED = Integer.MAX_VALUE;

    Node next;

    /** Tries to match here, then the rest of the pattern; i is a char index into the text. */
    abstract boolean match(Matcher m, int i);

    /** Wires this subtree to continue at `after` and returns the node to enter it by. */
    Node link(Node after) {
        this.next = after;
        return this;
    }

    /** Fewest chars this subtree can consume. */
    int min() {
        return 0;
    }

    /**
     * True when this subtree is a repeat the oracle gives no length (see Repeat), which also
     * stops it counting the nodes after it in the same sequence towards the shortest match.
     */
    boolean blocksMin() {
        return false;
    }

    /** Most chars this subtree can consume, or UNBOUNDED. */
    int max() {
        return 0;
    }

    static int add(int a, int b) {
        return a == UNBOUNDED || b == UNBOUNDED || a + b < 0 ? UNBOUNDED : a + b;
    }

    static int mul(int a, int b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a == UNBOUNDED || b == UNBOUNDED || (long) a * b > Integer.MAX_VALUE - 1) {
            return UNBOUNDED;
        }
        return a * b;
    }

    /** The end of a successful pattern match. */
    static final class Accept extends Node {
        boolean match(Matcher m, int i) {
            if (m.mustReachEnd && i != m.hi) {
                return false;
            }
            m.matchEnd = i;
            return true;
        }
    }

    /** The end of a lookahead or atomic body: records where it stopped and succeeds. */
    static final class Stop extends Node {
        boolean match(Matcher m, int i) {
            m.stopPos = i;
            return true;
        }
    }

    /** The end of a lookbehind body: succeeds only at the position the lookbehind was entered at. */
    static final class EndAt extends Node {
        boolean match(Matcher m, int i) {
            return i == m.behindTarget;
        }
    }

    /** Matches nothing: used for an empty branch. */
    static final class Empty extends Node {
        boolean match(Matcher m, int i) {
            return next.match(m, i);
        }

        Node link(Node after) {
            return after;
        }
    }

    /** A sequence of nodes. Only exists before linking. */
    static final class Seq extends Node {
        final Node[] items;

        Seq(Node[] items) {
            this.items = items;
        }

        boolean match(Matcher m, int i) {
            throw new IllegalStateException("unlinked sequence");
        }

        Node link(Node after) {
            Node head = after;
            for (int k = items.length - 1; k >= 0; k--) {
                head = items[k].link(head);
            }
            return head;
        }

        int min() {
            int n = 0;
            for (Node item : items) {
                n = add(n, item.min());
                if (item.blocksMin()) {
                    break;
                }
            }
            return n;
        }

        boolean blocksMin() {
            for (Node item : items) {
                if (item.blocksMin()) {
                    return true;
                }
            }
            return false;
        }

        int max() {
            int n = 0;
            for (Node item : items) {
                n = add(n, item.max());
            }
            return n;
        }
    }

    /** Alternation: the first branch that lets the rest of the pattern match wins. */
    static final class Alt extends Node {
        final Node[] branches;

        Alt(Node[] branches) {
            this.branches = branches;
        }

        boolean match(Matcher m, int i) {
            for (Node b : branches) {
                if (b.match(m, i)) {
                    return true;
                }
            }
            return false;
        }

        Node link(Node after) {
            for (int k = 0; k < branches.length; k++) {
                branches[k] = branches[k].link(after);
            }
            return this;
        }

        int min() {
            int n = UNBOUNDED;
            for (Node b : branches) {
                n = Math.min(n, b.min());
            }
            return n;
        }

        int max() {
            int n = 0;
            for (Node b : branches) {
                n = Math.max(n, b.max());
            }
            return n;
        }
    }
}
