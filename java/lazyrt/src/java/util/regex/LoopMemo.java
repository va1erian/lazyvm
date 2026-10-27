package java.util.regex;

/**
 * Remembers the loop states that failed, so a pattern like (?:x+x+)+y does not retry the same
 * position in every way it can be reached. That turns exponential backtracking into work
 * proportional to the text length times the iteration limit.
 *
 * <p>A state is a loop, how many iterations it has done (capped where more makes no
 * difference) and a position. The future of such a state only depends on those when the
 * pattern has no back references, the loop sits inside no other loop (whose count would be
 * part of the state) and not inside a lookaround (a lookbehind's body must end at a fixed
 * place). {@link #prepare} marks the loops that qualify. Nothing is remembered until a search
 * has taken {@link #AFTER} loop steps, so ordinary matches pay for a counter only.
 *
 * <p>A state that is skipped would have failed the same way, so the match found and its
 * groups are the same. What differs is the position at which a failed attempt leaves
 * Matcher.iterationEnd, which only a failed pathological search can show.
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
    boolean busy() {
        return ++work > AFTER;
    }

    /** True when the state (loop, count slot, position) is known to fail. */
    boolean failed(int loop, int slot, int pos) {
        long[] table = bits == null ? null : bits[loop];
        if (table == null || pos < lo || pos >= lo + span) {
            return false;
        }
        long bit = (long) slot * span + (pos - lo);
        return (table[(int) (bit >> 6)] & (1L << (bit & 63))) != 0;
    }

    void fail(int loop, int slot, int slots, int pos) {
        if (bits == null || pos < lo || pos >= lo + span || (long) slots * span > LIMIT_BITS) {
            return;
        }
        long[] table = bits[loop];
        if (table == null) {
            table = new long[(int) (((long) slots * span + 63) >> 6)];
            bits[loop] = table;
        }
        long bit = (long) slot * span + (pos - lo);
        table[(int) (bit >> 6)] |= 1L << (bit & 63);
    }

    /** The slot count for a loop: counts below the minimum differ, counts beyond it do not. */
    static int slots(int minCount, int maxCount) {
        return (maxCount == Node.UNBOUNDED ? minCount : maxCount) + 1;
    }

    /** Marks which loops of the linked tree may remember failures. */
    static void prepare(Node root) {
        if (!walk(root, false)) {
            walk(root, true);
        }
    }

    /**
     * Visits every node without recursion (a pattern can nest thousands of groups). Returns true
     * when a back reference is found; with `mark`, sets each loop's memoize flag instead.
     */
    private static boolean walk(Node root, boolean mark) {
        Node[] nodes = new Node[32];
        boolean[] shielded = new boolean[32];
        int top = 0;
        nodes[top++] = root;
        while (top > 0) {
            top--;
            Node n = nodes[top];
            boolean shield = shielded[top];
            if (n instanceof Atoms.BackRef) {
                return true;
            }
            if (n instanceof Repeat.Loop) {
                if (mark) {
                    ((Repeat.Loop) n).memoize(!shield);
                }
                shield = true;
            } else if (n instanceof GroupNodes.Look) {
                shield = true;
            }
            Node[] kids = children(n);
            if (top + kids.length > nodes.length) {
                int size = Math.max(nodes.length * 2, top + kids.length);
                Node[] biggerNodes = new Node[size];
                boolean[] biggerShield = new boolean[size];
                System.arraycopy(nodes, 0, biggerNodes, 0, top);
                System.arraycopy(shielded, 0, biggerShield, 0, top);
                nodes = biggerNodes;
                shielded = biggerShield;
            }
            for (Node kid : kids) {
                nodes[top] = kid;
                shielded[top++] = shield;
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
