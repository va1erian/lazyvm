package java.nio;

/** The CharBuffer implementation over one of its three backings. */
final class CharWindow extends CharBuffer {
    CharWindow(char[] store, int storeOffset, ByteBuffer bytes, CharSequence text,
            int mark, int pos, int lim, int cap, boolean readOnly) {
        super(store, storeOffset, bytes, text, mark, pos, lim, cap, readOnly);
    }

    public CharBuffer slice() {
        int n = remaining();
        return new CharWindow(store, storeOffset + position(), bytes, text, -1, 0, n, n, readOnly);
    }

    public CharBuffer duplicate() {
        return new CharWindow(store, storeOffset, bytes, text, markValue(), position(), limit(), capacity(), readOnly);
    }

    public CharBuffer asReadOnlyBuffer() {
        return new CharWindow(store, storeOffset, bytes, text, markValue(), position(), limit(), capacity(), true);
    }

    // Spec NB6: shares content, from position + start to position + end.
    public CharBuffer subSequence(int start, int end) {
        int p = position();
        if (start < 0 || end > remaining() || start > end) {
            throw new IndexOutOfBoundsException();
        }
        return new CharWindow(store, storeOffset, bytes, text, -1, p + start, p + end, capacity(), readOnly);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isDirect() {
        return bytes != null && bytes.isDirect();
    }

    public ByteOrder order() {
        return bytes == null ? ByteOrder.nativeOrder() : bytes.order();
    }

    private char load(int i) {
        if (store != null) {
            return store[storeOffset + i];
        }
        if (bytes != null) {
            return bytes.getChar((storeOffset + i) * 2);
        }
        return text.charAt(storeOffset + i);
    }

    private void save(int i, char c) {
        writable();
        if (store != null) {
            store[storeOffset + i] = c;
        } else {
            bytes.putChar((storeOffset + i) * 2, c);
        }
    }

    public char get() {
        return load(takeForGet(1));
    }

    public char get(int index) {
        return load(checkAt(index, 1));
    }

    public CharBuffer put(char c) {
        writable();
        save(takeForPut(1), c);
        return this;
    }

    public CharBuffer put(int index, char c) {
        writable();
        save(checkAt(index, 1), c);
        return this;
    }

    public CharBuffer compact() {
        writable();
        if (bytes != null) {
            bytes.checkViewCompact(storeOffset, limit(), 2);
        }
        int n = remaining();
        int p = position();
        if (store != null) {
            System.arraycopy(store, storeOffset + p, store, storeOffset, n);
        } else {
            for (int i = 0; i < n; i++) {
                save(i, load(p + i));
            }
        }
        compacted(n);
        return this;
    }
}
