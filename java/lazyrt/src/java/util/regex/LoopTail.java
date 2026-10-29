package java.util.regex;

/**
 * The end of one iteration of a {@link Repeat.Loop} body, where the loop decides to run the body
 * again or leave: a greedy loop tries to iterate first and to leave second, a lazy one the other
 * way round. When both failed, the state is remembered as failed (see LoopMemo).
 */
final class LoopTail extends Node {
    // A frame holds what the loop tried, ITERATED or LEFT (plus MEMO when the state's failure
    // is to be remembered), then the iteration start to restore after ITERATED or the
    // position after LEFT, then the event before the state (see LoopMemo.enter). The rest is
    // in the loop's counters again when the frame is resumed, because every frame pushed
    // after it has been undone: the count is one more than at the decision after ITERATED
    // (and the iteration start is the position), the same after LEFT.
    private static final int ITERATED = 0, LEFT = 1, MEMO = 2;

    private final int id;
    private final int minCount;
    private final int maxCount;
    private final boolean lazy;
    private final int slots;
    boolean memoizable;
    boolean records;
    Node head;

    LoopTail(int id, int minCount, int maxCount, boolean lazy) {
        this.slots = LoopMemo.slots(minCount, maxCount);
        this.id = id;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.lazy = lazy;
    }

    int go(Matcher m, int i) {
        int c = m.loopCount[id];
        if (c > 0 && i == m.loopStart[id]) {
            // The last iteration consumed nothing, so more would add nothing; the minimum
            // counts as met; observed on the oracle with (\n*?){2}a, see RegexQuantifiers).
            m.cont = next;
            return i;
        }
        if (c > 0 && records) {
            m.iterationEnd = i;
        }
        int memo = 0;
        int before = 0;
        if (memoizable && m.memo.step()) {
            if (m.memo.skip(m, id, c, slots, i)) {
                return FAIL;
            }
            memo = MEMO;
            before = m.memo.enter(m);
        }
        if (lazy && c >= minCount) {
            return leave(m, i, memo, before);
        }
        return iterate(m, c, i, memo, before);
    }

    int back(Matcher m, int at) {
        int[] d = m.stack.data;
        int memo = d[at] & MEMO;
        int before = d[at + 2];
        int c = m.loopCount[id];
        int i;
        if ((d[at] & LEFT) == 0) {
            c--;
            i = m.loopStart[id];
            m.loopCount[id] = c;
            m.loopStart[id] = d[at + 1];
            if (!lazy && c >= minCount) {
                return leave(m, i, memo, before);
            }
        } else {
            i = d[at + 1];
            if (lazy) {
                return iterate(m, c, i, memo, before);
            }
        }
        return failed(m, c, i, memo, before);
    }

    /** Runs the body once more when the count allows; a greedy loop leaves otherwise. */
    private int iterate(Matcher m, int c, int i, int memo, int before) {
        if (c >= minCount && c >= maxCount) {
            return lazy ? failed(m, c, i, memo, before) : leave(m, i, memo, before);
        }
        int at = m.stack.push(this);
        int[] d = m.stack.data;
        d[at] = ITERATED | memo;
        d[at + 1] = m.loopStart[id];
        d[at + 2] = before;
        m.loopCount[id] = c + 1;
        m.loopStart[id] = i;
        m.cont = head;
        return i;
    }

    /**
     * Leaves the loop at i. For a greedy loop that was the last option, so a frame is only
     * needed to remember the failure.
     */
    private int leave(Matcher m, int i, int memo, int before) {
        if (lazy || memo != 0) {
            int at = m.stack.push(this);
            int[] d = m.stack.data;
            d[at] = LEFT | memo;
            d[at + 1] = i;
            d[at + 2] = before;
        }
        m.cont = next;
        return i;
    }

    private int failed(Matcher m, int c, int i, int memo, int before) {
        if (memo != 0) {
            m.memo.failed(m, id, c, slots, i, before);
        }
        return FAIL;
    }
}
