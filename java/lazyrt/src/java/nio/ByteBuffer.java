package java.nio;

/**
 * A byte buffer over a Java array. LazyVM has heap buffers only: a "direct"
 * buffer is the same array-backed buffer that reports isDirect() and hides
 * its array, as the Javadoc lets a direct buffer do.
 */
public abstract class ByteBuffer extends Buffer implements Comparable<ByteBuffer> {
    final byte[] store;
    final int storeOffset;
    final boolean readOnly;
    final boolean direct;
    boolean bigEndian = true;
    /** For the bytes of a view: the capacity of the buffer the view was made from. */
    int sourceCapacity;

    /**
     * Observed (probes on Java 8): compact of a view of a heap buffer fails with
     * IllegalArgumentException when the view's start in the array plus its limit in bytes
     * passes the capacity of the buffer it was made from. That cannot happen unless the
     * buffer had an array offset. Direct views never fail.
     */
    final void checkViewCompact(int element0, int limit, int size) {
        long end = (long) storeOffset + (long) element0 * size + (long) limit * size;
        if (!direct && end > sourceCapacity) {
            throw new IllegalArgumentException();
        }
    }

    ByteBuffer(byte[] store, int storeOffset, int mark, int pos, int lim, int cap, boolean readOnly, boolean direct) {
        super(mark, pos, lim, cap);
        this.store = store;
        this.storeOffset = storeOffset;
        this.readOnly = readOnly;
        this.direct = direct;
    }

    public static ByteBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        return new ByteWindow(new byte[capacity], 0, -1, 0, capacity, capacity, false, false);
    }

    public static ByteBuffer allocateDirect(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        return new ByteWindow(new byte[capacity], 0, -1, 0, capacity, capacity, false, true);
    }

    // Spec NB3: position off, limit off + len, capacity a.length, arrayOffset 0.
    public static ByteBuffer wrap(byte[] array, int offset, int length) {
        checkRange(offset, length, array.length);
        return new ByteWindow(array, 0, -1, offset, offset + length, array.length, false, false);
    }

    public static ByteBuffer wrap(byte[] array) {
        return wrap(array, 0, array.length);
    }

    public abstract ByteBuffer slice();

    public abstract ByteBuffer duplicate();

    public abstract ByteBuffer asReadOnlyBuffer();

    public abstract byte get();

    public abstract ByteBuffer put(byte b);

    public abstract byte get(int index);

    public abstract ByteBuffer put(int index, byte b);

    public abstract ByteBuffer compact();

    public abstract boolean isDirect();

    public abstract char getChar();

    public abstract ByteBuffer putChar(char value);

    public abstract char getChar(int index);

    public abstract ByteBuffer putChar(int index, char value);

    public abstract short getShort();

    public abstract ByteBuffer putShort(short value);

    public abstract short getShort(int index);

    public abstract ByteBuffer putShort(int index, short value);

    public abstract int getInt();

    public abstract ByteBuffer putInt(int value);

    public abstract int getInt(int index);

    public abstract ByteBuffer putInt(int index, int value);

    public abstract long getLong();

    public abstract ByteBuffer putLong(long value);

    public abstract long getLong(int index);

    public abstract ByteBuffer putLong(int index, long value);

    public abstract float getFloat();

    public abstract ByteBuffer putFloat(float value);

    public abstract float getFloat(int index);

    public abstract ByteBuffer putFloat(int index, float value);

    public abstract double getDouble();

    public abstract ByteBuffer putDouble(double value);

    public abstract double getDouble(int index);

    public abstract ByteBuffer putDouble(int index, double value);

    public abstract CharBuffer asCharBuffer();

    public abstract ShortBuffer asShortBuffer();

    public abstract IntBuffer asIntBuffer();

    public abstract LongBuffer asLongBuffer();

    public abstract FloatBuffer asFloatBuffer();

    public abstract DoubleBuffer asDoubleBuffer();

    // Spec NB2: a bulk get that lacks room throws before moving anything.
    public ByteBuffer get(byte[] dst, int offset, int length) {
        checkRange(offset, length, dst.length);
        int p = takeForGet(length);
        System.arraycopy(store, storeOffset + p, dst, offset, length);
        return this;
    }

    public ByteBuffer get(byte[] dst) {
        return get(dst, 0, dst.length);
    }

    // Observed: read-only is checked before the source being this buffer. The copy moves
    // overlapping windows of one array as if through a temporary (System.arraycopy).
    public ByteBuffer put(ByteBuffer src) {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        if (src == this) {
            throw new IllegalArgumentException();
        }
        int n = src.remaining();
        int p = takeForPut(n);
        int s = src.takeForGet(n);
        System.arraycopy(src.store, src.storeOffset + s, store, storeOffset + p, n);
        return this;
    }

    public ByteBuffer put(byte[] src, int offset, int length) {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        checkRange(offset, length, src.length);
        int p = takeForPut(length);
        System.arraycopy(src, offset, store, storeOffset + p, length);
        return this;
    }

    public final ByteBuffer put(byte[] src) {
        return put(src, 0, src.length);
    }

    public final boolean hasArray() {
        return !readOnly && !direct;
    }

    public final byte[] array() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        if (direct) {
            throw new UnsupportedOperationException();
        }
        return store;
    }

    public final int arrayOffset() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        if (direct) {
            throw new UnsupportedOperationException();
        }
        return storeOffset;
    }

    public final ByteOrder order() {
        return bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
    }

    public final ByteBuffer order(ByteOrder bo) {
        bigEndian = bo == ByteOrder.BIG_ENDIAN;
        return this;
    }

    public String toString() {
        return describe();
    }

    // Spec NB5: h = 1, then h = 31 * h + element from the limit down to the position.
    public int hashCode() {
        int h = 1;
        for (int i = limit() - 1; i >= position(); i--) {
            h = 31 * h + get(i);
        }
        return h;
    }

    public boolean equals(Object ob) {
        if (!(ob instanceof ByteBuffer)) {
            return false;
        }
        ByteBuffer that = (ByteBuffer) ob;
        if (remaining() != that.remaining()) {
            return false;
        }
        for (int i = 0; i < remaining(); i++) {
            if (get(position() + i) != that.get(that.position() + i)) {
                return false;
            }
        }
        return true;
    }

    // Spec NB5: the first difference of the elements, else the difference of what remains.
    public int compareTo(ByteBuffer that) {
        int n = Math.min(remaining(), that.remaining());
        for (int i = 0; i < n; i++) {
            int d = get(position() + i) - that.get(that.position() + i);
            if (d != 0) {
                return d;
            }
        }
        return remaining() - that.remaining();
    }
}
