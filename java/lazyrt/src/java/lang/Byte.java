package java.lang;

public final class Byte extends Number implements Comparable<Byte> {
    public static final byte MIN_VALUE = -128;
    public static final byte MAX_VALUE = 127;
    public static final int SIZE = 8;
    public static final int BYTES = 1;

    @SuppressWarnings("unchecked")
    public static final Class<Byte> TYPE = (Class<Byte>) Class.getPrimitiveClass("byte");

    private final byte value;

    public Byte(byte value) {
        this.value = value;
    }

    public Byte(String s) throws NumberFormatException {
        this(parseByte(s, 10));
    }

    private static final class Cache {
        static final Byte[] CACHE = new Byte[256];

        static {
            for (int i = 0; i < 256; i++) {
                CACHE[i] = new Byte((byte) (i - 128));
            }
        }
    }

    public static Byte valueOf(byte b) {
        return Cache.CACHE[b + 128];
    }

    public static Byte valueOf(String s, int radix) throws NumberFormatException {
        return valueOf(parseByte(s, radix));
    }

    public static Byte valueOf(String s) throws NumberFormatException {
        return valueOf(parseByte(s, 10));
    }

    public static byte parseByte(String s, int radix) throws NumberFormatException {
        int i = Integer.parseInt(s, radix);
        if (i < MIN_VALUE || i > MAX_VALUE) {
            throw new NumberFormatException("Value out of range. Value:\"" + s + "\" Radix:" + radix);
        }
        return (byte) i;
    }

    public static byte parseByte(String s) throws NumberFormatException {
        return parseByte(s, 10);
    }

    public static Byte decode(String nm) throws NumberFormatException {
        int i = Integer.decode(nm).intValue();
        if (i < MIN_VALUE || i > MAX_VALUE) {
            throw new NumberFormatException("Value " + i + " out of range from input " + nm);
        }
        return valueOf((byte) i);
    }

    public static String toString(byte b) {
        return Integer.toString(b, 10);
    }

    public String toString() {
        return Integer.toString(value, 10);
    }

    public byte byteValue() {
        return value;
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

    public static int hashCode(byte value) {
        return value;
    }

    public boolean equals(Object obj) {
        return obj instanceof Byte && value == ((Byte) obj).value;
    }

    public int compareTo(Byte other) {
        return compare(value, other.value);
    }

    public static int compare(byte x, byte y) {
        return x - y;
    }

    public static int toUnsignedInt(byte x) {
        return x & 0xff;
    }

    public static long toUnsignedLong(byte x) {
        return x & 0xffL;
    }
}
