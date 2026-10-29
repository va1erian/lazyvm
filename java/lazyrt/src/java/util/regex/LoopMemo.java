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
 * <li>\G reads the end of the previous match, which a later search reads from the events of
 * this one, so it is left out to keep the argument simple.
 * <li>hitEnd and requireEnd only ever turn on during a search, and the first visit of a state
 * already turned on what a revisit would, so skipping it changes neither.
 * <li>The events of an attempt (Matcher.iterationEnd, where a failed search leaves the end of the
 * previous match, which the next find() and \G start from) are decided by the state too: a
 * failed state remembers the last event its paths recorded, and a skip records it again. A state
 * marks its entry with {@link #enter} (NO_EVENT, "nothing since"), so on failure the last event
 * is simply what Matcher.iterationEnd holds, and NO_EVENT means the value before the entry.
 * </ul>
 * So a skipped state changes nothing a caller can see, as long as its event fits in the event
 * table ({@link #EVENTS}); a state remembered beyond that is skipped without its event, which can
 * move where a failed search leaves off (the memo did that for every state before S5-09).
 *
 * <p>Failures are bits, up to {@link #LIMIT_BITS} states per loop; the last events are a hash
 * table beside them, holding only the states that recorded one.
 *
 * <p>{@link #prepare} marks the loops that qualify. Nothing is remembered, or even allocated,
 * until a search has taken {@link #AFTER} loop steps, so ordinary matches pay for a counter only.
 */
final class LoopMemo {
    static final int AFTER = 20000;
    /** Largest failure table, in states, one loop may use; a bigger one is not worth the memory. */
    private static final long LIMIT_BITS = 1L << 26;
    /** Most failed states whose last event is remembered (12 MB of table when full). */
    private static final int EVENTS = 1 << 19;
    /** Matcher.iterationEnd while nothing has been recorded since a state was entered. */
    private static final int NO_EVENT = -2;

    private int work;
    private int lo;
    private int span;
    private int loops;
    /** Per loop, a bit per state (count slot times position): set when the state failed. */
    private long[][] bits;
    /**
     * The last event of each failed state that recorded one, by open addressing: a key is the
     * loop and the state's bit, plus 1 so that 0 marks a free entry.
     */
    private long[] eventKeys;
    private int[] eventPositions;
    private int eventCount;

    /** The memo of one search over the region, for a pattern with this many loops. */
    LoopMemo(int regionStart, int regionEnd, int loops) {
        lo = regionStart;
        span = regionEnd - regionStart + 1;
        this.loops = loops;
    }

    /** Counts one loop step; true once the search has done enough of them to start remembering. */
    boolean step() {
        return ++work > AFTER;
    }

    /**
     * When the state (loop, iterations done, position) is known to fail, records the event its
     * paths left, as running them would, and returns true.
     */
    boolean skip(Matcher m, int loop, int count, int slots, int pos) {
        long[] table = bits == null ? null : bits[loop];
        if (table == null || pos < lo || pos >= lo + span) {
            return false;
        }
        long bit = bit(count, slots, pos);
        if ((table[(int) (bit >> 6)] & (1L << (bit & 63))) == 0) {
            return false;
        }
        int event = eventCount == 0 ? NO_EVENT : event(key(loop, bit));
        if (event != NO_EVENT) {
            m.iterationEnd = event;
        }
        return true;
    }

    /** Marks the entry of a state whose failure is to be remembered; returns what to pass to failed. */
    int enter(Matcher m) {
        int before = m.iterationEnd;
        m.iterationEnd = NO_EVENT;
        return before;
    }

    /** The state entered with `before` (see enter) failed: remembers it with its last event. */
    void failed(Matcher m, int loop, int count, int slots, int pos, int before) {
        int last = m.iterationEnd;
        if (last == NO_EVENT) {
            m.iterationEnd = before;
        }
        if (loops == 0 || pos < lo || pos >= lo + span || (long) slots * span > LIMIT_BITS) {
            return;
        }
        if (bits == null) {
            bits = new long[loops][];
        }
        long[] table = bits[loop];
        if (table == null) {
            table = new long[(int) (((long) slots * span + 63) >> 6)];
            bits[loop] = table;
        }
        long bit = bit(count, slots, pos);
        if (last != NO_EVENT) {
            putEvent(key(loop, bit), last);
        }
        table[(int) (bit >> 6)] |= 1L << (bit & 63);
    }

    private long bit(int count, int slots, int pos) {
        return (long) (count < slots ? count : slots - 1) * span + (pos - lo);
    }

    private static long key(int loop, long bit) {
        return ((long) loop << 40 | bit) + 1;
    }

    private static int slotOf(long key, int mask) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
    }

    /** The event recorded for the key, or NO_EVENT. */
    private int event(long key) {
        int mask = eventKeys.length - 1;
        for (int k = slotOf(key, mask); eventKeys[k] != 0; k = (k + 1) & mask) {
            if (eventKeys[k] == key) {
                return eventPositions[k];
            }
        }
        return NO_EVENT;
    }

    /** Records an event; once the table is full, further states are remembered without one. */
    private void putEvent(long key, int position) {
        if (eventCount >= EVENTS) {
            return;
        }
        if (eventKeys == null || 2 * (eventCount + 1) > eventKeys.length) {
            growEvents();
        }
        int mask = eventKeys.length - 1;
        int k = slotOf(key, mask);
        while (eventKeys[k] != 0) {
            k = (k + 1) & mask;
        }
        eventKeys[k] = key;
        eventPositions[k] = position;
        eventCount++;
    }

    private void growEvents() {
        long[] oldKeys = eventKeys;
        int[] oldPositions = eventPositions;
        int size = oldKeys == null ? 256 : oldKeys.length * 2;
        eventKeys = new long[size];
        eventPositions = new int[size];
        eventCount = 0;
        if (oldKeys != null) {
            for (int k = 0; k < oldKeys.length; k++) {
                if (oldKeys[k] != 0) {
                    putEvent(oldKeys[k], oldPositions[k]);
                }
            }
        }
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
