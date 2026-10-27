package java.nio;

/**
 * Position, limit, capacity and mark shared by every buffer kind. The index
 * helpers below are what the typed buffers use to check a relative or
 * absolute access before they touch their storage.
 */
public abstract class Buffer {
    private int mark = -1;
    private int position;
    private int limit;
    private final int capacity;

    Buffer(int mark, int position, int limit, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
        limit(limit);
        position(position);
        if (mark >= 0) {
            this.mark = mark;
        }
    }

    public final int capacity() {
        return capacity;
    }

    public final int position() {
        return position;
    }

    // Spec NB1: outside [0, limit] is an IllegalArgumentException with no message;
    // a position below the mark drops it, one equal to it keeps it.
    public final Buffer position(int newPosition) {
        if (newPosition < 0 || newPosition > limit) {
            throw new IllegalArgumentException();
        }
        if (mark > newPosition) {
            mark = -1;
        }
        position = newPosition;
        return this;
    }

    public final int limit() {
        return limit;
    }

    // Spec NB1: a limit below the position moves it; below the mark drops the mark.
    public final Buffer limit(int newLimit) {
        if (newLimit < 0 || newLimit > capacity) {
            throw new IllegalArgumentException();
        }
        limit = newLimit;
        if (position > newLimit) {
            position = newLimit;
        }
        if (mark > newLimit) {
            mark = -1;
        }
        return this;
    }

    public final Buffer mark() {
        mark = position;
        return this;
    }

    public final Buffer reset() {
        if (mark < 0) {
            throw new InvalidMarkException();
        }
        position = mark;
        return this;
    }

    public final Buffer clear() {
        position = 0;
        limit = capacity;
        mark = -1;
        return this;
    }

    public final Buffer flip() {
        limit = position;
        position = 0;
        mark = -1;
        return this;
    }

    public final Buffer rewind() {
        position = 0;
        mark = -1;
        return this;
    }

    public final int remaining() {
        return limit - position;
    }

    public final boolean hasRemaining() {
        return position < limit;
    }

    public abstract boolean isReadOnly();

    public abstract boolean hasArray();

    public abstract Object array();

    public abstract int arrayOffset();

    public abstract boolean isDirect();

    /** The mark, for views that copy it (-1 when unset). */
    final int markValue() {
        return mark;
    }

    final void dropMark() {
        mark = -1;
    }

    /** Claims `n` elements for a relative get: the old position, or underflow. */
    final int takeForGet(int n) {
        if (limit - position < n) {
            throw new BufferUnderflowException();
        }
        int p = position;
        position = p + n;
        return p;
    }

    /** Claims `n` elements for a relative put: the old position, or overflow. */
    final int takeForPut(int n) {
        if (limit - position < n) {
            throw new BufferOverflowException();
        }
        int p = position;
        position = p + n;
        return p;
    }

    // Spec NB2: an absolute access of `n` elements must lie in [0, limit].
    final int checkAt(int index, int n) {
        if (index < 0 || n > limit - index) {
            throw new IndexOutOfBoundsException();
        }
        return index;
    }

    /** The range check of the bulk get/put and wrap overloads. */
    static void checkRange(int off, int len, int size) {
        if ((off | len) < 0 || len > size - off) {
            throw new IndexOutOfBoundsException();
        }
    }

    /** "[pos=P lim=L cap=C]" after the class name (Spec NB5: the name is free). */
    final String describe() {
        return getClass().getName() + "[pos=" + position + " lim=" + limit + " cap=" + capacity + "]";
    }

    /** Called by compact: the remaining `n` elements now start at 0. */
    final void compacted(int n) {
        position = n;
        limit = capacity;
        mark = -1;
    }
}
