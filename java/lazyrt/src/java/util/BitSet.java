package java.util;

/** A growable vector of bits in 64-bit words (docs/design/util-data.md section 6, BS1-BS9). */
public class BitSet implements Cloneable, java.io.Serializable {
    private long[] words;
    /** BS4: made with an explicit size (or a clone or get(from, to) of one) and not grown since. */
    private transient boolean sized;

    /** BS1: one word. */
    public BitSet() { words = new long[1]; }

    /** BS1: just enough words for nbits, and sized. */
    public BitSet(int nbits) {
        if (nbits < 0) {
            throw new NegativeArraySizeException("nbits < 0: " + nbits);
        }
        words = new long[(int) ((nbits + 63L) >>> 6)];
        sized = true;
    }

    private BitSet(long[] words) { this.words = words; }

    /** BS5: bit k of element j is bit 64 * j + k; trailing zero words are dropped. */
    public static BitSet valueOf(long[] longs) {
        return new BitSet(Arrays.copyOf(longs, BitWords.inUse(longs, longs.length)));
    }

    /** BS5: bit k of element j is bit 8 * j + k. */
    public static BitSet valueOf(byte[] bytes) {
        long[] w = new long[(bytes.length + 7) / 8];
        for (int i = 0; i < bytes.length; i++) {
            w[i >> 3] |= (bytes[i] & 0xffL) << (8 * (i & 7));
        }
        return valueOf(w);
    }

    public byte[] toByteArray() {
        int n = inUse();
        if (n == 0) {
            return new byte[0];
        }
        int bytes = 8 * (n - 1) + (64 - Long.numberOfLeadingZeros(words[n - 1]) + 7) / 8;
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (words[i >> 3] >>> (8 * (i & 7)));
        }
        return out;
    }

    public long[] toLongArray() { return Arrays.copyOf(words, inUse()); }

    private int inUse() { return BitWords.inUse(words, words.length); }

    /** BS2: room for word w, growing to max(2k, w + 1) words. */
    private void ensure(int w) {
        if (w >= words.length) {
            words = Arrays.copyOf(words, Math.max(2 * words.length, w + 1));
            sized = false;
        }
    }

    private static void checkIndex(int bitIndex) {
        if (bitIndex < 0) {
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);
        }
    }

    /** BS8: the range checks, in order. */
    private static void checkRange(int from, int to) {
        if (from < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + from);
        }
        if (to < 0) {
            throw new IndexOutOfBoundsException("toIndex < 0: " + to);
        }
        if (from > to) {
            throw new IndexOutOfBoundsException("fromIndex: " + from + " > toIndex: " + to);
        }
    }

    public void flip(int bitIndex) {
        checkIndex(bitIndex);
        ensure(bitIndex >> 6);
        words[bitIndex >> 6] ^= 1L << bitIndex;
    }

    public void flip(int fromIndex, int toIndex) {
        checkRange(fromIndex, toIndex);
        if (fromIndex < toIndex) {
            ensure((toIndex - 1) >> 6);
            BitWords.apply(words, fromIndex, toIndex, BitWords.FLIP);
        }
    }

    public void set(int bitIndex) {
        checkIndex(bitIndex);
        ensure(bitIndex >> 6);
        words[bitIndex >> 6] |= 1L << bitIndex;
    }

    public void set(int bitIndex, boolean value) {
        if (value) {
            set(bitIndex);
        } else {
            clear(bitIndex);
        }
    }

    public void set(int fromIndex, int toIndex) {
        checkRange(fromIndex, toIndex);
        if (fromIndex < toIndex) {
            ensure((toIndex - 1) >> 6);
            BitWords.apply(words, fromIndex, toIndex, BitWords.SET);
        }
    }

    public void set(int fromIndex, int toIndex, boolean value) {
        if (value) {
            set(fromIndex, toIndex);
        } else {
            clear(fromIndex, toIndex);
        }
    }

    public void clear(int bitIndex) {
        checkIndex(bitIndex);
        if (bitIndex >> 6 < words.length) {
            words[bitIndex >> 6] &= ~(1L << bitIndex);
        }
    }

    /** BS2: clearing never grows the array. */
    public void clear(int fromIndex, int toIndex) {
        checkRange(fromIndex, toIndex);
        long end = Math.min(toIndex, 64L * words.length);
        if (fromIndex < end) {
            BitWords.apply(words, fromIndex, (int) end, BitWords.CLEAR);
        }
    }

    /** BS2: nothing shrinks. */
    public void clear() { Arrays.fill(words, 0L); }

    public boolean get(int bitIndex) {
        checkIndex(bitIndex);
        int w = bitIndex >> 6;
        return w < words.length && (words[w] & 1L << bitIndex) != 0;
    }

    /** BS6: a sized set of bits from..min(to, length()) - 1, moved down by from. */
    public BitSet get(int fromIndex, int toIndex) {
        checkRange(fromIndex, toIndex);
        int len = length();
        if (fromIndex == toIndex || len <= fromIndex) {
            return new BitSet(0);
        }
        int t = Math.min(toIndex, len);
        BitSet r = new BitSet(t - fromIndex);
        BitWords.extract(words, fromIndex, t, r.words);
        return r;
    }

    /** BS7, BS8: the search methods check their index first, then scan whole words. */
    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        return BitWords.next(words, fromIndex, false);
    }

    public int nextClearBit(int fromIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        }
        return BitWords.next(words, fromIndex, true);
    }

    public int previousSetBit(int fromIndex) {
        checkPrevious(fromIndex);
        return BitWords.previous(words, fromIndex, false);
    }

    public int previousClearBit(int fromIndex) {
        checkPrevious(fromIndex);
        return BitWords.previous(words, fromIndex, true);
    }

    private static void checkPrevious(int fromIndex) {
        if (fromIndex < -1) {
            throw new IndexOutOfBoundsException("fromIndex < -1: " + fromIndex);
        }
    }

    /** BS3: from the highest non-zero word, so the array's spare words never count. */
    public int length() {
        int n = inUse();
        return n == 0 ? 0 : 64 * n - Long.numberOfLeadingZeros(words[n - 1]);
    }

    public boolean isEmpty() { return inUse() == 0; }

    public int cardinality() {
        int c = 0;
        for (long w : words) {
            c += Long.bitCount(w);
        }
        return c;
    }

    public boolean intersects(BitSet set) {
        for (int i = Math.min(words.length, set.words.length) - 1; i >= 0; i--) {
            if ((words[i] & set.words[i]) != 0) {
                return true;
            }
        }
        return false;
    }

    public void and(BitSet set) {
        long[] other = set.words;
        for (int i = 0; i < words.length; i++) {
            words[i] = i < other.length ? words[i] & other[i] : 0;
        }
    }

    /** BS2: grows to the other set's words in use, not to its size. */
    public void or(BitSet set) {
        long[] other = set.words;
        int n = set.inUse();
        if (n > 0) {
            ensure(n - 1);
        }
        for (int i = 0; i < n; i++) {
            words[i] |= other[i];
        }
    }

    public void xor(BitSet set) {
        long[] other = set.words;
        int n = set.inUse();
        if (n > 0) {
            ensure(n - 1);
        }
        for (int i = 0; i < n; i++) {
            words[i] ^= other[i];
        }
    }

    public void andNot(BitSet set) {
        long[] other = set.words;
        for (int i = Math.min(words.length, other.length) - 1; i >= 0; i--) {
            words[i] &= ~other[i];
        }
    }

    /** BS9: the Javadoc's formula over the words in use. */
    @Override
    public int hashCode() {
        long h = 1234;
        for (int i = inUse(); --i >= 0;) {
            h ^= words[i] * (i + 1);
        }
        return (int) ((h >> 32) ^ h);
    }

    public int size() { return 64 * words.length; }

    /** BS9: the same bits, whatever the sizes. */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BitSet)) {
            return false;
        }
        BitSet o = (BitSet) obj;
        int n = inUse();
        if (n != o.inUse()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (words[i] != o.words[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * BS4: a sized set keeps its words. Any other set is first trimmed to its words in use, the
     * receiver included (BS2's one shrinking operation), so later growth starts from that size.
     */
    @Override
    public Object clone() {
        if (!sized) {
            words = Arrays.copyOf(words, inUse());
        }
        try {
            BitSet copy = (BitSet) super.clone();
            copy.words = words.clone();
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    /** BS5: set bits in increasing order, walked with nextSetBit. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = nextSetBit(0); i >= 0; i = i == Integer.MAX_VALUE ? -1 : nextSetBit(i + 1)) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(i);
        }
        return sb.append('}').toString();
    }
}
