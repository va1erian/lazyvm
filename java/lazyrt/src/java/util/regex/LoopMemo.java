package java.util.regex;

/**
 * Remembers the loop states that failed, so a pattern like (?:x+x+)+y does not retry the same
 * position in every way it can be reached. That turns exponential backtracking into work
 * proportional to the text length times the iteration limit.
 *
 * <p>A state is a loop, how many iterations it has done (capped where more makes no
 * difference: {@link #slots}) and a position. Skipping a state that failed before is sound only
 * if what happens from it is decided by those three things and by nothing the search changed on
 * the way there.
 *
 * <p>The memo is on only for a pattern made entirely of nodes whose behaviour is understood to
 * leave a state's outcome decided by (loop, count, position): literals, classes and their
 * repeats, line breaks, plain and capturing groups, alternation, loops, lookarounds, atomic
 * groups and possessive repeats, and the anchors other than \G, provided no capture is kept
 * after its path fails (see {@link #keepsCaptures}). Everything else (back references, \G, a
 * kept capture, and any node added later) switches it off for the whole pattern. Why each
 * allowed node is safe:
 * <ul>
 * <li>The text, region and flags are fixed for the whole search, so literals, classes, line
 * breaks and the anchors answer the same each time.
 * <li>A group sets its capture on the way in and restores it when the path fails, and nothing
 * reads a capture while matching (no back reference is allowed), so the groups of a failed path
 * never show. A capture in a lookaround or atomic group (a possessive loop is one) or by a
 * possessive repeat of one char stays set when the path later fails, which is why none may
 * appear anywhere in the pattern.
 * <li>A lookaround, atomic group or possessive repeat without such a capture answers from the
 * position alone: its body reads only the text, and what the body leaves behind when it
 * matches (the counters of the loops inside it) is read by nothing outside it.
 * <li>An enclosing loop would put its own count and iteration start into the state, so only
 * loops that sit inside no other loop are marked, and no loop inside a lookaround or atomic
 * group, whose body ends somewhere else (a lookbehind's where it was entered). The check runs
 * on the whole tree, so a node after the loop counts.
 * <li>\G reads the end of the previous match, which the end-of-attempt events (Matcher.iterationEnd) move, so it would make a
 * skipped state show.
 * <li>hitEnd and requireEnd only ever turn on during a search, and the first visit of a state
 * already turned on what a revisit would, so skipping it changes neither.
 * </ul>
 * What is left to differ is the position at which a failed attempt leaves
 * Matcher.iterationEnd, which only a failed pathological search can show, and then only to a
 * \G in another pattern installed with usePattern (documented in docs/regex.md).
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
        if (!walk(tree, false, true)) {
            walk(tree, true, true);
        }
    }

    /**
     * True when a capture in the tree stays set after its path fails, so that which paths a search
     * tried can show: a capturing group inside a lookaround or atomic group (a possessive loop is
     * one), or a possessive repeat of one char that captures (see Backtrack; RegexGroups).
     */
    static boolean keepsCaptures(Node tree) {
        return walk(tree, false, false);
    }

    // Flags of a node during the walk: inside a loop, lookaround or atomic group (its loops are
    // not marked), and inside a lookaround or atomic group (its captures are kept).
    private static final int NESTED = 1, SCOPED = 2;

    /**
     * Visits every node without recursion (a pattern can nest thousands of groups). Returns true
     * when a capture is kept (see keepsCaptures) or, with `memo`, when the pattern holds a node
     * outside the allowed set (see the class comment); with `mark`, sets each loop's memoize flag
     * instead: only loops outside every other loop, lookaround and atomic group qualify.
     */
    private static boolean walk(Node root, boolean mark, boolean memo) {
        Node[] nodes = new Node[32];
        int[] flags = new int[32];
        int top = 0;
        nodes[top++] = root;
        while (top > 0) {
            top--;
            Node n = nodes[top];
            int f = flags[top];
            if (keepsCapture(n, f) || (memo && !allowed(n))) {
                return true;
            }
            if (n instanceof Repeat.Loop) {
                if (mark) {
                    ((Repeat.Loop) n).memoize(f == 0);
                }
                f |= NESTED;
            }
            if (n instanceof GroupNodes.Look || n instanceof GroupNodes.Atomic) {
                f |= NESTED | SCOPED;
            }
            Node[] kids = children(n);
            if (top + kids.length > nodes.length) {
                int size = Math.max(nodes.length * 2, top + kids.length);
                Node[] biggerNodes = new Node[size];
                int[] biggerFlags = new int[size];
                System.arraycopy(nodes, 0, biggerNodes, 0, top);
                System.arraycopy(flags, 0, biggerFlags, 0, top);
                nodes = biggerNodes;
                flags = biggerFlags;
            }
            for (Node kid : kids) {
                nodes[top] = kid;
                flags[top++] = f;
            }
        }
        return false;
    }

    /** Whether n keeps its capture after its path fails, given the flags it is visited with. */
    private static boolean keepsCapture(Node n, int flags) {
        boolean scoped = (flags & SCOPED) != 0;
        if (n instanceof Repeat.CharRepeat) {
            Repeat.CharRepeat r = (Repeat.CharRepeat) n;
            return r.keepsCapture() || (scoped && r.group >= 0);
        }
        return scoped && n instanceof GroupNodes.Group;
    }

    /** The whitelist of the class comment; a node of any other class disables the memo. */
    private static boolean allowed(Node n) {
        if (n instanceof Anchor) {
            return !((Anchor) n).isLastMatch();
        }
        return n instanceof Node.Seq || n instanceof Node.Alt || n instanceof Node.Empty || n instanceof Atoms.One
            || n instanceof Atoms.Str || n instanceof Atoms.LineBreak || n instanceof GroupNodes.Group
            || n instanceof Repeat.Loop || n instanceof Repeat.CharRepeat || n instanceof GroupNodes.Look
            || n instanceof GroupNodes.Atomic;
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
