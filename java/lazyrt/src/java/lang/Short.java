package java.lang;

public final class Short extends Number implements Comparable<Short> {
    public static final short MIN_VALUE = -32768;
    public static final short MAX_VALUE = 32767;
    public static final int SIZE = 16;
    public static final int BYTES = 2;

    @SuppressWarnings("unchecked")
    public static final Class<Short> TYPE = (Class<Short>) Class.getPrimitiveClass("short");

    private final short value;

    public Short(short value) {
        this.value = value;
    }

    public Short(String s) throws NumberFormatException {
        this(parseShort(s, 10));
    }

    private static final class Cache {
        static final Short[] CACHE = new Short[256];

        static {
            for (int i = 0; i < 256; i++) {
                CACHE[i] = new Short((short) (i - 128));
            }
        }
    }

    public static Short valueOf(short s) {
        if (s >= -128 && s <= 127) {
            return Cache.CACHE[s + 128];
        }
        return new Short(s);
    }

    public static Short valueOf(String s, int radix) throws NumberFormatException {
        return valueOf(parseShort(s, radix));
    }

    public static Short valueOf(String s) throws NumberFormatException {
        return valueOf(parseShort(s, 10));
    }

    public static short parseShort(String s, int radix) throws NumberFormatException {
        int i = Integer.parseInt(s, radix);
        if (i < MIN_VALUE || i > MAX_VALUE) {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (short) i;
    }

    public static short parseShort(String s) throws NumberFormatException {
        return parseShort(s, 10);
    }

    public static Short decode(String nm) throws NumberFormatException {
        int i = Integer.decode(nm).intValue();
        if (i < MIN_VALUE || i > MAX_VALUE) {
            throw new NumberFormatException("Value " + i + " out of range from input " + nm);
        }
        return valueOf((short) i);
    }

    public static String toString(short s) {
        return Integer.toString(s, 10);
    }

    public String toString() {
        return Integer.toString(value, 10);
    }

    public byte byteValue() {
        return (byte) value;
    }

    public short shortValue() {
        return value;
    }

    public int intValue() {
        return value;
    }

    public long longValue() {
        return value;
    }

    public float floatValue() {
        return value;
    }

    public double doubleValue() {
        return value;
    }

    public int hashCode() {
        return value;
    }

    public static int hashCode(short value) {
        return value;
    }

    public boolean equals(Object obj) {
        return obj instanceof Short && value == ((Short) obj).value;
    }

    public int compareTo(Short other) {
        return compare(value, other.value);
    }

    public static int compare(short x, short y) {
        return x - y;
    }

    public static short reverseBytes(short i) {
        return (short) (((i & 0xFF00) >> 8) | (i << 8));
    }

    public static int toUnsignedInt(short x) {
        return x & 0xffff;
    }

    public static long toUnsignedLong(short x) {
        return x & 0xffffL;
    }
}
