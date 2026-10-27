package java.util.regex;

/**
 * Remembers the loop states that failed, so a pattern like (?:x+x+)+y does not retry the same
 * position in every way it can be reached. That turns exponential backtracking into work
 * proportional to the text length times the iteration limit.
 *
 * <p>A state is a loop, how many iterations it has done (capped where more makes no
 * difference: {@link #slots}) and a position. Skipping a state that failed before is sound only
 * if what happens from it is decided by those three things and by nothing the search changed on
 * the way there, and if the groups left over by a failed path do not show in the result:
 * <ul>
 * <li>The text, region and flags are fixed for the whole search, so position-only nodes (anchors,
 * classes, , \G) answer the same each time.
 * <li>An enclosing loop would put its own count and iteration start into the state, so only
 * loops that sit inside no other loop are marked.
 * <li>A lookbehind body is run from many starts towards one fixed end, and a lookahead body ends
 * at its own marker, so a loop inside any lookaround is not marked.
 * <li>A back reference reads a group that earlier choices set, so a pattern with one is not
 * marked at all. The check covers the whole tree, so a back reference after the loop counts.
 * <li>A group inside a lookaround or an atomic group, and a possessive repeat of a group, keep
 * their capture when the path later fails, so which paths were tried shows in the groups of the
 * match: a pattern with one is not marked at all.
 * <li>hitEnd and requireEnd only ever turn on during a search, and the first visit of a state
 * already turned on what a revisit would, so skipping it changes neither.
 * </ul>
 * What is left to differ is the position at which a failed attempt leaves
 * Matcher.iterationEnd, which only a failed pathological search can show.
 *
 * <p>{@link #prepare} marks the loops that qualify. Nothing is remembered, or even allocated,
 * until a search has taken {@link #AFTER} loop steps, so ordinary matches pay for a counter only.
 */
final class LoopMemo {
    static final int AFTER = 20000;
    /** Largest table, in bits, one loop may use; a bigger one is not worth the memory. */
    private static final long LIMIT_BITS = 1L << 26;

    private int work;
    private int lo;
    private int span;
    private long[][] bits;

    void reset(int regionStart, int regionEnd, int loops) {
        work = 0;
        lo = regionStart;
        span = regionEnd - regionStart + 1;
        bits = null;
        if (loops > 0) {
            bits = new long[loops][];
        }
    }

    /** Counts one loop step; true once the search has done enough of them to start remembering. */
    boolean step() {
        return ++work > AFTER;
    }

    /** True once the search has done enough steps to remember failures (without counting one). */
    boolean active() {
        return work > AFTER;
    }

    /** True when the state (loop, iterations done, position) is known to fail. */
    boolean failed(int loop, int count, int slots, int pos) {
        long[] table = bits == null ? null : bits[loop];
        if (table == null || pos < lo || pos >= lo + span) {
            return false;
        }
        long bit = bit(count, slots, pos);
        return (table[(int) (bit >> 6)] & (1L << (bit & 63))) != 0;
    }

    void fail(int loop, int count, int slots, int pos) {
        if (bits == null || pos < lo || pos >= lo + span || (long) slots * span > LIMIT_BITS) {
            return;
        }
        long[] table = bits[loop];
        if (table == null) {
            table = new long[(int) (((long) slots * span + 63) >> 6)];
            bits[loop] = table;
        }
        long bit = bit(count, slots, pos);
        table[(int) (bit >> 6)] |= 1L << (bit & 63);
    }

    private long bit(int count, int slots, int pos) {
        return (long) (count < slots ? count : slots - 1) * span + (pos - lo);
    }

    /** The slot count for a loop: counts below the minimum differ, counts beyond it do not. */
    static int slots(int minCount, int maxCount) {
        return (maxCount == Node.UNBOUNDED ? minCount : maxCount) + 1;
    }

    /**
     * Marks which loops of the tree may remember failures. It must run before the tree is linked,
     * because linking replaces the children of some nodes by their linked heads.
     */
    static void prepare(Node tree) {
        if (!walk(tree, false)) {
            walk(tree, true);
        }
    }

    private static final int SHIELDED = 1;
    private static final int KEEPS_CAPTURES = 2;

    /**
     * Visits every node without recursion (a pattern can nest thousands of groups). Returns true
     * when the pattern is unsafe as a whole (see the class comment); with `mark`, sets each
     * loop's memoize flag instead.
     */
    private static boolean walk(Node root, boolean mark) {
        Node[] nodes = new Node[32];
        int[] context = new int[32];
        int top = 0;
        nodes[top++] = root;
        while (top > 0) {
            top--;
            Node n = nodes[top];
            int ctx = context[top];
            if (n instanceof Atoms.BackRef
                || (n instanceof GroupNodes.Group && (ctx & KEEPS_CAPTURES) != 0)
                || (n instanceof Repeat.CharRepeat && ((Repeat.CharRepeat) n).keepsCapture())) {
                return true;
            }
            if (n instanceof Repeat.Loop) {
                if (mark) {
                    ((Repeat.Loop) n).memoize((ctx & SHIELDED) == 0);
                }
                ctx |= SHIELDED;
            } else if (n instanceof GroupNodes.Look) {
                ctx |= SHIELDED | KEEPS_CAPTURES;
            } else if (n instanceof GroupNodes.Atomic) {
                ctx |= KEEPS_CAPTURES;
            }
            Node[] kids = children(n);
            if (top + kids.length > nodes.length) {
                int size = Math.max(nodes.length * 2, top + kids.length);
                Node[] biggerNodes = new Node[size];
                int[] biggerContext = new int[size];
                System.arraycopy(nodes, 0, biggerNodes, 0, top);
                System.arraycopy(context, 0, biggerContext, 0, top);
                nodes = biggerNodes;
                context = biggerContext;
            }
            for (Node kid : kids) {
                nodes[top] = kid;
                context[top++] = ctx;
            }
        }
        return false;
    }

    private static Node[] children(Node n) {
        if (n instanceof Node.Seq) {
            return ((Node.Seq) n).items;
        }
        if (n instanceof Node.Alt) {
            return ((Node.Alt) n).branches;
        }
        if (n instanceof GroupNodes.Group) {
            return new Node[] {((GroupNodes.Group) n).body()};
        }
        if (n instanceof GroupNodes.Atomic) {
            return new Node[] {((GroupNodes.Atomic) n).body()};
        }
        if (n instanceof GroupNodes.Look) {
            return new Node[] {((GroupNodes.Look) n).body()};
        }
        if (n instanceof Repeat.Loop) {
            return new Node[] {((Repeat.Loop) n).body()};
        }
        return new Node[0];
    }
}
