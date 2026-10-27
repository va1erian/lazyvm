package java.nio;

import java.io.IOException;

/**
 * A char buffer over a char array, a view of a ByteBuffer's bytes, or a
 * read-only live view of a CharSequence. The same layout as the generated
 * typed buffers (tools/buffers.py), plus the CharSequence and text API.
 */
public abstract class CharBuffer extends Buffer
        implements Comparable<CharBuffer>, Appendable, CharSequence, Readable {
    final char[] store;
    final int storeOffset;
    final ByteBuffer bytes;
    final CharSequence text;
    final boolean readOnly;

    CharBuffer(char[] store, int storeOffset, ByteBuffer bytes, CharSequence text,
            int mark, int pos, int lim, int cap, boolean readOnly) {
        super(mark, pos, lim, cap);
        this.store = store;
        this.storeOffset = storeOffset;
        this.bytes = bytes;
        this.text = text;
        this.readOnly = readOnly;
    }

    static CharBuffer overBytes(ByteBuffer b, boolean readOnly) {
        int n = b.remaining() / 2;
        return new CharWindow(null, 0, b, null, -1, 0, n, n, readOnly);
    }

    public static CharBuffer allocate(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException();
        }
        return new CharWindow(new char[capacity], 0, null, null, -1, 0, capacity, capacity, false);
    }

    public static CharBuffer wrap(char[] array, int offset, int length) {
        checkRange(offset, length, array.length);
        return new CharWindow(array, 0, null, null, -1, offset, offset + length, array.length, false);
    }

    public static CharBuffer wrap(char[] array) {
        return wrap(array, 0, array.length);
    }

    // Spec NB6: a read-only live view, position start and limit end.
    public static CharBuffer wrap(CharSequence csq, int start, int end) {
        int n = csq.length();
        if (start < 0 || end > n || start > end) {
            throw new IndexOutOfBoundsException();
        }
        return new CharWindow(null, 0, null, csq, -1, start, end, n, true);
    }

    public static CharBuffer wrap(CharSequence csq) {
        return wrap(csq, 0, csq.length());
    }

    public abstract CharBuffer slice();

    public abstract CharBuffer duplicate();

    public abstract CharBuffer asReadOnlyBuffer();

    public abstract char get();

    public abstract CharBuffer put(char c);

    public abstract char get(int index);

    public abstract CharBuffer put(int index, char c);

    public abstract CharBuffer compact();

    public abstract boolean isDirect();

    public abstract ByteOrder order();

    public abstract CharBuffer subSequence(int start, int end);

    // Spec NB6: copies the smaller remaining; -1 when empty, 0 when the target is full.
    // Observed: an empty buffer is -1 even when the target is itself.
    public int read(CharBuffer target) throws IOException {
        int n = remaining();
        if (n == 0) {
            return -1;
        }
        if (target == this) {
            refuseSelf();
        }
        n = Math.min(n, target.remaining());
        if (n == 0) {
            return 0;
        }
        // Observed: a read-only target is refused before this buffer's position moves.
        target.writable();
        int p = takeForGet(n);
        if (target.store != null) {
            copyOut(p, target.store, target.storeOffset + target.takeForPut(n), n);
        } else {
            for (int i = 0; i < n; i++) {
                target.put(get(p + i));
            }
        }
        return n;
    }

    public CharBuffer get(char[] dst, int offset, int length) {
        checkRange(offset, length, dst.length);
        int p = takeForGet(length);
        copyOut(p, dst, offset, length);
        return this;
    }

    /** Copies `n` chars from index `p` into `dst` at `at`, in bulk where the backing allows. */
    private void copyOut(int p, char[] dst, int at, int n) {
        int from = storeOffset + p;
        if (store != null) {
            System.arraycopy(store, from, dst, at, n);
        } else if (text instanceof String) {
            ((String) text).getChars(from, from + n, dst, at);
        } else if (text instanceof StringBuilder) {
            ((StringBuilder) text).getChars(from, from + n, dst, at);
        } else if (text instanceof StringBuffer) {
            ((StringBuffer) text).getChars(from, from + n, dst, at);
        } else {
            for (int i = 0; i < n; i++) {
                dst[at + i] = get(p + i);
            }
        }
    }

    /**
     * Observed: views of heap bytes and text views report themselves (IAE) before their
     * read-only state; arrays and direct views the read-only state first.
     */
    private boolean lazyReadOnly() {
        return text != null || (bytes != null && !bytes.direct);
    }

    private void refuseSelf() {
        if (!lazyReadOnly()) {
            writable();
        }
        throw new IllegalArgumentException();
    }

    public CharBuffer get(char[] dst) {
        return get(dst, 0, dst.length);
    }

    // Observed: between arrays, and between direct views of one order, overlapping windows
    // copy as if through a temporary; other backings go char by char.
    public CharBuffer put(CharBuffer src) {
        if (src == this) {
            refuseSelf();
        }
        writable();
        int n = src.remaining();
        int p = takeForPut(n);
        int s = src.takeForGet(n);
        if (store != null) {
            src.copyOut(s, store, storeOffset + p, n);
        } else if (ByteBuffer.sameDirectOrder(bytes, src.bytes)) {
            bytes.copyElements(src.bytes, (src.storeOffset + s) * 2, (storeOffset + p) * 2, n * 2);
        } else {
            for (int i = 0; i < n; i++) {
                put(p + i, src.get(s + i));
            }
        }
        return this;
    }

    // Observed: views of heap bytes and text views find the read-only state only at a char.
    public CharBuffer put(char[] src, int offset, int length) {
        if (!lazyReadOnly()) {
            writable();
        }
        checkRange(offset, length, src.length);
        if (length > 0) {
            if (length > remaining()) {
                throw new BufferOverflowException();
            }
            writable();
        }
        int p = takeForPut(length);
        if (store != null) {
            System.arraycopy(src, offset, store, storeOffset + p, length);
        } else {
            for (int i = 0; i < length; i++) {
                put(p + i, src[offset + i]);
            }
        }
        return this;
    }

    public final CharBuffer put(char[] src) {
        return put(src, 0, src.length);
    }

    // Spec NB6: start and end index the string; an overflow leaves the position.
    public CharBuffer put(String src, int start, int end) {
        // Observed: here the range is checked before the read-only state.
        if (start < 0 || end > src.length() || start > end) {
            throw new IndexOutOfBoundsException();
        }
        writable();
        int p = takeForPut(end - start);
        if (store != null) {
            src.getChars(start, end, store, storeOffset + p);
        } else {
            for (int i = start; i < end; i++) {
                put(p + i - start, src.charAt(i));
            }
        }
        return this;
    }

    public final CharBuffer put(String src) {
        return put(src, 0, src.length());
    }

    public CharBuffer append(CharSequence csq) {
        String s = String.valueOf(csq);
        return put(s, 0, s.length());
    }

    public CharBuffer append(CharSequence csq, int start, int end) {
        CharSequence cs = csq == null ? "null" : csq;
        String s = cs.subSequence(start, end).toString();
        return put(s, 0, s.length());
    }

    public CharBuffer append(char c) {
        return put(c);
    }

    final void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public final boolean hasArray() {
        return store != null && !readOnly;
    }

    public final char[] array() {
        if (store == null) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return store;
    }

    public final int arrayOffset() {
        if (store == null) {
            throw new UnsupportedOperationException();
        }
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
        return storeOffset;
    }

    public final int length() {
        return remaining();
    }

    public final char charAt(int index) {
        if (index < 0 || index >= remaining()) {
            throw new IndexOutOfBoundsException();
        }
        return get(position() + index);
    }

    /** The remaining chars (Spec NB5), without moving the position. */
    public String toString() {
        if (store != null) {
            return new String(store, storeOffset + position(), remaining());
        }
        if (text != null) {
            return text.subSequence(storeOffset + position(), storeOffset + limit()).toString();
        }
        StringBuilder sb = new StringBuilder(remaining());
        for (int i = position(); i < limit(); i++) {
            sb.append(get(i));
        }
        return sb.toString();
    }

    public int hashCode() {
        int h = 1;
        for (int i = limit() - 1; i >= position(); i--) {
            h = 31 * h + get(i);
        }
        return h;
    }

    public boolean equals(Object ob) {
        if (!(ob instanceof CharBuffer)) {
            return false;
        }
        CharBuffer that = (CharBuffer) ob;
        return remaining() == that.remaining() && compareTo(that) == 0;
    }

    public int compareTo(CharBuffer that) {
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
