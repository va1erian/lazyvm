package java.util.regex;

/**
 * The backtracking stack of one matcher, kept in growable arrays on the heap, so a match can run
 * as many loop iterations as memory allows instead of being bounded by the VM's frame limit.
 *
 * <p>A match runs as a loop here: each node's {@link Node#go} either moves on (it returns a
 * position and sets {@code Matcher.cont} to the node to run there), fails, or ends the match. A
 * node that still has a choice to try, or state to undo when the match backs out of it, first
 * pushes a frame: its owner and up to {@link #INTS} ints. On failure the loop pops frames from
 * the top and calls {@link Node#back} on each owner, newest first, until one moves on again. That
 * is the order in which a recursive matcher would return through the same nodes, so every flag
 * and capture is set and undone in the same order.
 *
 * <p>A lookaround or atomic group opens a scope: a frame that its body's end (Node.Stop or
 * Node.EndAt) returns to. When the body matches, every frame above the scope is dropped without
 * being undone, which is why a capture made in such a body stays set when the match later backs
 * out (observed on the oracle, RegexGroups).
 */
final class Backtrack {
    /** Returned by go and back: backtrack. */
    static final int FAIL = -1;
    /** Returned by go: the whole match succeeded. */
    static final int DONE = -2;
    /** Ints a frame may use. Frames have one size, so a frame's ints start at its index times 8. */
    static final int INTS = 7;
    private static final int SHIFT = 3;
    /** Where a scope frame keeps the scope that was open before it (Node.Scope restores it). */
    static final int OUTER = 7;

    /** Frames kept between attempts; a deeper stack is let go when its attempt ends. */
    private static final int KEPT = 1 << 14;

    private Node[] owners = new Node[64];
    /** The frames' ints; a caller re-reads this field after every push, which may replace it. */
    int[] data = new int[64 << SHIFT];
    /** One object per frame, made the first time a frame carries one (see FlatLoop). */
    private Object[] refs;
    /** The frames below this index may still hold an object in refs. */
    private int refsTop;
    private int frames;
    /** The frame of the innermost open scope, or -1. */
    int scope;

    /** Matches from `node` at i, with an empty stack; true when the match reached Node.Accept. */
    boolean run(Matcher m, Node node, int i) {
        frames = 0;
        scope = -1;
        while (true) {
            i = node.go(m, i);
            while (i < 0) {
                if (i == DONE) {
                    release();
                    return true;
                }
                int f = frames - 1;
                if (f < 0) {
                    release();
                    return false;
                }
                frames = f;
                i = owners[f].back(m, f << SHIFT);
            }
            node = m.cont;
        }
    }

    /**
     * Lets go of what the attempt left behind, which the recursive engine dropped with its Java
     * frames: the loop states frames still point to, and a stack a deep match grew, so that a
     * matcher that lives on does not keep them.
     */
    private void release() {
        if (owners.length > KEPT) {
            owners = new Node[64];
            data = new int[64 << SHIFT];
            refs = null;
            refsTop = 0;
        }
        for (; refsTop > 0; refsTop--) {
            refs[refsTop - 1] = null;
        }
    }

    /**
     * Pushes a frame for `owner` and returns where its ints go in {@link #data}. A back call that
     * pushes again gets the place of the frame it was called for, so it reads that frame first.
     */
    int push(Node owner) {
        int f = frames;
        if (f == owners.length) {
            grow();
        }
        owners[f] = owner;
        frames = f + 1;
        return f << SHIFT;
    }

    /** Pushes the frame of a scope (see the class comment). */
    int pushScope(Node.Scope owner) {
        int f = frames;
        if (f == owners.length) {
            grow();
        }
        owners[f] = owner;
        frames = f + 1;
        int at = f << SHIFT;
        data[at + OUTER] = scope;
        scope = f;
        return at;
    }

    /**
     * The body of the innermost scope matched: drops its frame and every frame above it, keeping
     * their effects, and lets the owner go on.
     */
    int closeScope(Matcher m) {
        int f = scope;
        int at = f << SHIFT;
        scope = data[at + OUTER];
        frames = f;
        return ((Node.Scope) owners[f]).matched(m, at);
    }

    /** Attaches an object to the frame pushed last. */
    void setRef(Object o) {
        if (refs == null || refs.length < owners.length) {
            Object[] bigger = new Object[owners.length];
            if (refs != null) {
                System.arraycopy(refs, 0, bigger, 0, refs.length);
            }
            refs = bigger;
        }
        refs[frames - 1] = o;
        if (frames > refsTop) {
            refsTop = frames;
        }
    }

    /** The object of the frame just popped for a back call, released from the stack. */
    Object takeRef() {
        Object o = refs[frames];
        refs[frames] = null;
        return o;
    }

    private void grow() {
        if (frames > (Integer.MAX_VALUE >> SHIFT) / 2) {
            // The ints of twice as many frames would not fit in one array.
            throw new OutOfMemoryError("Regex backtracking stack");
        }
        int size = frames * 2;
        Node[] biggerOwners = new Node[size];
        int[] biggerData = new int[size << SHIFT];
        System.arraycopy(owners, 0, biggerOwners, 0, frames);
        System.arraycopy(data, 0, biggerData, 0, data.length);
        owners = biggerOwners;
        data = biggerData;
    }
}
