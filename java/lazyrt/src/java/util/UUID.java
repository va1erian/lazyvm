package java.util;

/** A 128-bit identifier (docs/design/util-data.md section 5, rules U1-U8). */
public final class UUID implements java.io.Serializable, Comparable<UUID> {
    private final long mostSigBits;
    private final long leastSigBits;

    public UUID(long mostSigBits, long leastSigBits) {
        this.mostSigBits = mostSigBits;
        this.leastSigBits = leastSigBits;
    }

    /** U8: version 4, variant 2, from the VM's entropy source (util.md R3). */
    public static UUID randomUUID() {
        long msb = lazy.internal.VM.entropy();
        long lsb = lazy.internal.VM.entropy();
        return new UUID(msb & ~0xf000L | 0x4000L, lsb & ~(3L << 62) | 1L << 63);
    }

    /** U7: an MD5 name-based UUID with version 3 and variant 2. */
    public static UUID nameUUIDFromBytes(byte[] name) {
        byte[] d = lazy.internal.Md5.digest(name);
        d[6] = (byte) (d[6] & 0x0f | 0x30);
        d[8] = (byte) (d[8] & 0x3f | 0x80);
        return new UUID(bigEndian(d, 0), bigEndian(d, 8));
    }

    private static long bigEndian(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = v << 8 | (b[off + i] & 0xff);
        }
        return v;
    }

    /** U2, U3: five fields, each read as Long.decode reads a 0x number, combined unmasked. */
    public static UUID fromString(String name) {
        String[] f = name.split("-");
        if (f.length != 5) {
            throw new IllegalArgumentException("Invalid UUID string: " + name);
        }
        long[] v = new long[5];
        for (int i = 0; i < 5; i++) {
            v[i] = Long.decode("0x" + f[i]);
        }
        return new UUID(v[0] << 32 | v[1] << 16 | v[2], v[3] << 48 | v[4]);
    }

    public long getLeastSignificantBits() { return leastSigBits; }

    public long getMostSignificantBits() { return mostSigBits; }

    /** U4: the version nibble, read with a signed shift as the oracle does. */
    public int version() { return (int) ((mostSigBits >> 12) & 0xf); }

    /** U4: 0, 2, 6 or 7 from the top one to three bits of the low word. */
    public int variant() {
        if (leastSigBits >= 0) {
            return 0;
        }
        int top3 = (int) (leastSigBits >>> 61);
        return top3 < 6 ? 2 : top3;
    }

    /** U5: time fields are only meaningful for version 1, whatever the variant. */
    public long timestamp() {
        requireTimeBased();
        return (mostSigBits & 0xfffL) << 48 | ((mostSigBits >>> 16) & 0xffffL) << 32 | mostSigBits >>> 32;
    }

    public int clockSequence() {
        requireTimeBased();
        return (int) ((leastSigBits >>> 48) & 0x3fff);
    }

    public long node() {
        requireTimeBased();
        return leastSigBits & 0xffffffffffffL;
    }

    private void requireTimeBased() {
        if (version() != 1) {
            throw new UnsupportedOperationException("Not a time-based UUID");
        }
    }

    /** U1: the 8-4-4-4-12 form, each field zero padded in lower case. */
    @Override
    public String toString() {
        return hex(mostSigBits >>> 32, 8) + "-" + hex(mostSigBits >>> 16, 4) + "-" + hex(mostSigBits, 4) + "-"
            + hex(leastSigBits >>> 48, 4) + "-" + hex(leastSigBits, 12);
    }

    /** The low `digits` (at most 15) hex digits of v, lower case and zero padded (U1). */
    private static String hex(long v, int digits) {
        return FormatHex.pad(Long.toHexString(v & ((1L << (4 * digits)) - 1)), digits);
    }

    /** U1: both words folded together, so equal UUIDs hash alike. */
    @Override
    public int hashCode() {
        long x = mostSigBits ^ leastSigBits;
        return (int) (x >>> 32) ^ (int) x;
    }

    /** U6: equal only to a UUID with the same two words. */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof UUID)) {
            return false;
        }
        UUID o = (UUID) obj;
        return mostSigBits == o.mostSigBits && leastSigBits == o.leastSigBits;
    }

    /** U6: signed comparisons, high word first. */
    @Override
    public int compareTo(UUID val) {
        int c = Long.compare(mostSigBits, val.mostSigBits);
        return c != 0 ? Integer.signum(c) : Integer.signum(Long.compare(leastSigBits, val.leastSigBits));
    }
}
