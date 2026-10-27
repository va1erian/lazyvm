package java.nio;

/** The one ByteBuffer implementation: a window of a byte array, maybe read-only. */
final class ByteWindow extends ByteBuffer {
    ByteWindow(byte[] store, int storeOffset, int mark, int pos, int lim, int cap, boolean readOnly, boolean direct) {
        super(store, storeOffset, mark, pos, lim, cap, readOnly, direct);
    }

    // Spec NB3: slices and duplicates are BIG_ENDIAN whatever the source's order.
    public ByteBuffer slice() {
        int p = position();
        int n = remaining();
        return new ByteWindow(store, storeOffset + p, -1, 0, n, n, readOnly, direct);
    }

    public ByteBuffer duplicate() {
        return copy(readOnly);
    }

    public ByteBuffer asReadOnlyBuffer() {
        return copy(true);
    }

    private ByteBuffer copy(boolean ro) {
        return new ByteWindow(store, storeOffset, markValue(), position(), limit(), capacity(), ro, direct);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public boolean isDirect() {
        return direct;
    }

    private void writable() {
        if (readOnly) {
            throw new ReadOnlyBufferException();
        }
    }

    public byte get() {
        return store[storeOffset + takeForGet(1)];
    }

    public byte get(int index) {
        return store[storeOffset + checkAt(index, 1)];
    }

    public ByteBuffer put(byte b) {
        writable();
        store[storeOffset + takeForPut(1)] = b;
        return this;
    }

    public ByteBuffer put(int index, byte b) {
        writable();
        store[storeOffset + checkAt(index, 1)] = b;
        return this;
    }

    // Spec NB1: compact moves what remains to 0; position = its count, limit = capacity.
    public ByteBuffer compact() {
        writable();
        int n = remaining();
        System.arraycopy(store, storeOffset + position(), store, storeOffset, n);
        compacted(n);
        return this;
    }

    /** `size` bytes at `index` (already checked) as an unsigned value in this buffer's order. */
    private long load(int index, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) {
            int b = store[storeOffset + index + (bigEndian ? i : size - 1 - i)] & 0xff;
            v = (v << 8) | b;
        }
        return v;
    }

    private void save(int index, int size, long v) {
        writable();
        for (int i = size - 1; i >= 0; i--) {
            store[storeOffset + index + (bigEndian ? i : size - 1 - i)] = (byte) v;
            v >>>= 8;
        }
    }

    private long getRel(int size) {
        return load(takeForGet(size), size);
    }

    private ByteBuffer putRel(int size, long v) {
        writable();
        save(takeForPut(size), size, v);
        return this;
    }

    private long getAbs(int index, int size) {
        return load(checkAt(index, size), size);
    }

    private ByteBuffer putAbs(int index, int size, long v) {
        writable();
        save(checkAt(index, size), size, v);
        return this;
    }

    public char getChar() {
        return (char) getRel(2);
    }

    public ByteBuffer putChar(char value) {
        return putRel(2, value);
    }

    public char getChar(int index) {
        return (char) getAbs(index, 2);
    }

    public ByteBuffer putChar(int index, char value) {
        return putAbs(index, 2, value);
    }

    public short getShort() {
        return (short) getRel(2);
    }

    public ByteBuffer putShort(short value) {
        return putRel(2, value);
    }

    public short getShort(int index) {
        return (short) getAbs(index, 2);
    }

    public ByteBuffer putShort(int index, short value) {
        return putAbs(index, 2, value);
    }

    public int getInt() {
        return (int) getRel(4);
    }

    public ByteBuffer putInt(int value) {
        return putRel(4, value);
    }

    public int getInt(int index) {
        return (int) getAbs(index, 4);
    }

    public ByteBuffer putInt(int index, int value) {
        return putAbs(index, 4, value);
    }

    public long getLong() {
        return getRel(8);
    }

    public ByteBuffer putLong(long value) {
        return putRel(8, value);
    }

    public long getLong(int index) {
        return getAbs(index, 8);
    }

    public ByteBuffer putLong(int index, long value) {
        return putAbs(index, 8, value);
    }

    // Spec NB4: floats and doubles keep their NaN bits.
    public float getFloat() {
        return Float.intBitsToFloat(getInt());
    }

    public ByteBuffer putFloat(float value) {
        return putInt(Float.floatToRawIntBits(value));
    }

    public float getFloat(int index) {
        return Float.intBitsToFloat(getInt(index));
    }

    public ByteBuffer putFloat(int index, float value) {
        return putInt(index, Float.floatToRawIntBits(value));
    }

    public double getDouble() {
        return Double.longBitsToDouble(getLong());
    }

    public ByteBuffer putDouble(double value) {
        return putLong(Double.doubleToRawLongBits(value));
    }

    public double getDouble(int index) {
        return Double.longBitsToDouble(getLong(index));
    }

    public ByteBuffer putDouble(int index, double value) {
        return putLong(index, Double.doubleToRawLongBits(value));
    }

    /** The bytes a view sees: from the position, in this buffer's order at creation (Spec NB4). */
    private ByteBuffer viewBytes() {
        ByteBuffer b = slice();
        b.bigEndian = bigEndian;
        b.sourceCapacity = capacity();
        return b;
    }

    public CharBuffer asCharBuffer() {
        return CharBuffer.overBytes(viewBytes(), readOnly);
    }

    public ShortBuffer asShortBuffer() {
        return ShortBuffer.overBytes(viewBytes(), readOnly);
    }

    public IntBuffer asIntBuffer() {
        return IntBuffer.overBytes(viewBytes(), readOnly);
    }

    public LongBuffer asLongBuffer() {
        return LongBuffer.overBytes(viewBytes(), readOnly);
    }

    public FloatBuffer asFloatBuffer() {
        return FloatBuffer.overBytes(viewBytes(), readOnly);
    }

    public DoubleBuffer asDoubleBuffer() {
        return DoubleBuffer.overBytes(viewBytes(), readOnly);
    }
}
