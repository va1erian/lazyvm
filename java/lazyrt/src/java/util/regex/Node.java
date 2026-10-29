package java.util.regex;

/**
 * A node of the compiled pattern. The parser builds a tree, then {@link #link}
 * wires every node to the node that must match after it, so matching is a walk
 * through `next` pointers, run by {@link Backtrack}: a node consumes input and
 * hands on to `next`, or fails. A node that changes matcher state, or has
 * another way to match, pushes a frame first, and its {@link #back} restores the
 * state or tries the next way when the match backtracks into that frame.
 */
abstract class Node {
    static final int UNBOUNDED = Integer.MAX_VALUE;
    static final int FAIL = Backtrack.FAIL;
    static final int DONE = Backtrack.DONE;

    Node next;

    /**
     * Matches this node at char index i. Returns where the match goes on, with m.cont set to the
     * node to run there; FAIL to backtrack; or DONE when the whole pattern matched.
     */
    abstract int go(Matcher m, int i);

    /**
     * Resumes a frame this node pushed, once everything pushed after it has failed; `at` is where
     * its ints lie in m.stack.data. Returns as go does (never DONE).
     */
    int back(Matcher m, int at) {
        throw new IllegalStateException("no frame");
    }

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

    /** A lookaround or atomic group: its body ends by returning to its scope (see Backtrack). */
    abstract static class Scope extends Node {
        /** The body matched; `at` is where the scope frame's ints lie. Returns as go does. */
        abstract int matched(Matcher m, int at);

        /** The body failed (from the start the frame holds); returns as back does. */
        abstract int failed(Matcher m, int at);

        final int back(Matcher m, int at) {
            // The scope around this one is the innermost again.
            Backtrack s = m.stack;
            s.scope = s.data[at + Backtrack.OUTER];
            return failed(m, at);
        }
    }

    /** The end of a successful pattern match. */
    static final class Accept extends Node {
        int go(Matcher m, int i) {
            if (m.mustReachEnd && i != m.hi) {
                return FAIL;
            }
            m.matchEnd = i;
            return DONE;
        }
    }

    /** The end of a lookahead or atomic body: records where it stopped and closes the scope. */
    static final class Stop extends Node {
        int go(Matcher m, int i) {
            m.stopPos = i;
            return m.stack.closeScope(m);
        }
    }

    /** The end of a lookbehind body: matches only at the position the lookbehind was entered at. */
    static final class EndAt extends Node {
        int go(Matcher m, int i) {
            return i == m.behindTarget ? m.stack.closeScope(m) : FAIL;
        }
    }

    /** Matches nothing: used for an empty branch. */
    static final class Empty extends Node {
        int go(Matcher m, int i) {
            m.cont = next;
            return i;
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

        int go(Matcher m, int i) {
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

        // A frame holds the next branch to try and the position.
        int go(Matcher m, int i) {
            if (branches.length > 1) {
                int at = m.stack.push(this);
                int[] d = m.stack.data;
                d[at] = 1;
                d[at + 1] = i;
            }
            m.cont = branches[0];
            return i;
        }

        int back(Matcher m, int at) {
            int[] d = m.stack.data;
            int k = d[at];
            int i = d[at + 1];
            if (k + 1 < branches.length) {
                int again = m.stack.push(this);
                m.stack.data[again] = k + 1;
            }
            m.cont = branches[k];
            return i;
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
