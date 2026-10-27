package java.util.regex;

/**
 * A repeated group whose body is flat: literals, single-char classes, repeats of
 * a single-char class and zero-width anchors, with alternatives allowed only at
 * the top. Such a loop runs without recursion: the backtracking state of every
 * iteration lives in an int array, so (\w+\s?)* or (?:ab|c)* over thousands of
 * iterations does not consume Java frames. The match order is the same as the
 * recursive {@link Repeat.Loop}'s: iterations are tried greedily (or lazily),
 * within one iteration the first alternative and the longest repeat first, and
 * an iteration that consumed nothing ends the loop.
 */
final class FlatLoop extends Node {
    // Frame layout, in ints: the loop position, the iterations done, the start of the
    // iteration that led here, the phase, the group values to restore when the frame is
    // dropped, the enumerator (branch, resume flag), then positions and repeat counts.
    private static final int POS = 0, DONE = 1, LAST = 2, PHASE = 3, OLD_START = 4, OLD_END = 5;
    private static final int BRANCH = 6, RESUME = 7, DATA = 8;
    private static final int ENTER = 0, ITERATE = 1, LEAVE = 2, EXIT_ONLY = 3;

    private final Node[][] branches;
    private final int group;
    private final int minCount;
    private final int maxCount;
    private final boolean lazy;
    private final int width;
    private final int frameSize;
    private final int id;
    private final int slots;
    /** Set by LoopMemo.prepare: this loop may remember the states that failed. */
    boolean memoizable;
    /** Set by Repeat.Loop: iterations of this loop count as events for Matcher.iterationEnd. */
    boolean records;

    private FlatLoop(Node[][] branches, int group, int minCount, int maxCount, boolean lazy, int id) {
        this.id = id;
        this.slots = LoopMemo.slots(minCount, maxCount);
        this.branches = branches;
        this.group = group;
        this.minCount = minCount;
        this.maxCount = maxCount;
        this.lazy = lazy;
        int w = 0;
        for (Node[] b : branches) {
            w = Math.max(w, b.length);
        }
        this.width = w;
        this.frameSize = DATA + (w + 1) + w;
    }

    /** The flat form of a loop over `body`, or null when the body is not flat. */
    static FlatLoop of(Node body, int minCount, int maxCount, boolean lazy, int id) {
        int group = -1;
        Node inner = body;
        if (body instanceof GroupNodes.Group) {
            group = ((GroupNodes.Group) body).number();
            inner = ((GroupNodes.Group) body).body();
        }
        Node[] alternatives = inner instanceof Node.Alt ? ((Node.Alt) inner).branches : new Node[] {inner};
        Node[][] branches = new Node[alternatives.length][];
        for (int k = 0; k < alternatives.length; k++) {
            branches[k] = items(alternatives[k]);
            if (branches[k] == null) {
                return null;
            }
        }
        return new FlatLoop(branches, group, minCount, maxCount, lazy, id);
    }

    private static Node[] items(Node n) {
        if (n instanceof Node.Empty) {
            return new Node[0];
        }
        if (n instanceof Node.Seq) {
            Node[] items = ((Node.Seq) n).items;
            for (Node item : items) {
                if (!simple(item)) {
                    return null;
                }
            }
            return items;
        }
        return simple(n) ? new Node[] {n} : null;
    }

    private static boolean simple(Node n) {
        if (n instanceof Repeat.CharRepeat) {
            return ((Repeat.CharRepeat) n).group < 0;
        }
        return n instanceof Atoms.One || n instanceof Atoms.Str || n instanceof Anchor
            || n instanceof Atoms.LineBreak;
    }

    /**
     * Each frame is one decision point of the loop: "at position i after c iterations". It
     * enumerates the ways the body can match at i, and for each way pushes a frame for the
     * next decision; a frame that runs out of ways leaves the loop at i (unless lazy, which
     * left first) and is dropped, which resumes its parent.
     */
    boolean match(Matcher m, int start) {
        int[] st = new int[frameSize * 8];
        int f = 0;
        st[PHASE] = ENTER;
        st[POS] = start;
        st[LAST] = -1;
        while (true) {
            int i = st[f + POS];
            int c = st[f + DONE];
            boolean required = c < minCount;
            switch (st[f + PHASE]) {
                case ENTER:
                    if (c > 0 && i == st[f + LAST]) {
                        st[f + PHASE] = EXIT_ONLY;
                        break;
                    }
                    if (memoizable && m.memo.step() && m.memo.failed(id, c, slots, i)) {
                        f = drop(m, st, f);
                        if (f < 0) {
                            return false;
                        }
                        break;
                    }
                    st[f + PHASE] = ITERATE;
                    if (lazy && !required && next.match(m, i)) {
                        return true;
                    }
                    break;
                case ITERATE:
                    int e = required || c < maxCount ? solve(m, st, f) : -1;
                    if (e < 0) {
                        st[f + PHASE] = LEAVE;
                        break;
                    }
                    st[f + RESUME] = 1;
                    int child = f + frameSize;
                    if (child + frameSize > st.length) {
                        int[] bigger = new int[st.length * 2];
                        System.arraycopy(st, 0, bigger, 0, st.length);
                        st = bigger;
                    }
                    if (records) {
                        m.iterationEnd = e;
                    }
                    st[child + POS] = e;
                    st[child + DONE] = c + 1;
                    st[child + LAST] = i;
                    st[child + PHASE] = ENTER;
                    st[child + RESUME] = 0;
                    if (group >= 0) {
                        st[child + OLD_START] = m.groups[2 * group];
                        st[child + OLD_END] = m.groups[2 * group + 1];
                        m.groups[2 * group] = i;
                        m.groups[2 * group + 1] = e;
                    }
                    f = child;
                    break;
                case LEAVE:
                    if (!required && !lazy && next.match(m, i)) {
                        return true;
                    }
                    if (memoizable && m.memo.active()) {
                        m.memo.fail(id, c, slots, i);
                    }
                    f = drop(m, st, f);
                    if (f < 0) {
                        return false;
                    }
                    break;
                default:
                    if (next.match(m, i)) {
                        return true;
                    }
                    f = drop(m, st, f);
                    if (f < 0) {
                        return false;
                    }
                    break;
            }
        }
    }

    /** Drops frame f, undoing its capture, and returns its parent (-1 for the first frame). */
    private int drop(Matcher m, int[] st, int f) {
        if (f == 0) {
            return -1;
        }
        if (group >= 0) {
            m.groups[2 * group] = st[f + OLD_START];
            m.groups[2 * group + 1] = st[f + OLD_END];
        }
        return f - frameSize;
    }

    /** The next way the body can match at the frame's position: its end, or -1 when none is left. */
    private int solve(Matcher m, int[] st, int f) {
        int i = st[f + POS];
        int posAt = f + DATA;
        int countAt = f + DATA + width + 1;
        int b;
        int k;
        boolean retry;
        if (st[f + RESUME] == 0) {
            b = 0;
            k = 0;
            retry = false;
            st[posAt] = i;
        } else {
            b = st[f + BRANCH];
            k = branches[b].length - 1;
            retry = true;
        }
        while (true) {
            if (k < 0) {
                if (++b == branches.length) {
                    return -1;
                }
                k = 0;
                retry = false;
                st[posAt] = i;
            }
            Node[] items = branches[b];
            if (k == items.length) {
                st[f + BRANCH] = b;
                return st[posAt + k];
            }
            int end = attempt(m, items[k], st[posAt + k], st, countAt + k, st[posAt + k + 1], retry);
            if (end < 0) {
                k--;
                retry = true;
            } else {
                st[posAt + k + 1] = end;
                k++;
                retry = false;
            }
        }
    }

    /** One way for `item` to match at p, or -1; `retry` asks for the next way after `prevEnd`. */
    private static int attempt(Matcher m, Node item, int p, int[] st, int countAt, int prevEnd, boolean retry) {
        if (item instanceof Repeat.CharRepeat) {
            return ((Repeat.CharRepeat) item).nextWay(m, p, st, countAt, prevEnd, retry);
        }
        if (retry) {
            return -1;
        }
        if (item instanceof Atoms.One) {
            int w = Repeat.widthAt(m, ((Atoms.One) item).cc, p);
            return w == 0 ? -1 : p + w;
        }
        if (item instanceof Atoms.LineBreak) {
            int w = Atoms.LineBreak.widthAt(m, p);
            return w == 0 ? -1 : p + w;
        }
        if (item instanceof Atoms.Str) {
            return ((Atoms.Str) item).matchEnd(m, p);
        }
        return ((Anchor) item).holds(m, p) ? p : -1;
    }
}
