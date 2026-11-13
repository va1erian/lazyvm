package lazy.internal.msg;

/**
 * The Messenger body format: a run of TLV fields, each an 8-byte header (a little-endian {@code
 * kind | id << 8}, then the payload length) and its payload (LazyOS docs/midl.md, "Wire
 * encoding"). {@link WireWriter} writes it and {@link WireReader} reads it; the codecs {@code
 * midlc --java} generates are written against these two.
 */
public final class Wire {
    public static final int BOOL = 1, I32 = 2, I64 = 3, U32 = 4, U64 = 5, F64 = 6, STRING = 7,
            BYTES = 8, ARRAY = 9, STRUCT = 10, MAP = 11, OPTION = 12, ERROR = 13, HANDLE = 14,
            BUFFER = 15;
    /** The reply field id of the standard error, the same in every interface. */
    public static final int ERROR_FIELD = 15;
    /** The largest field id the 16 bits of a tag can hold. */
    public static final int MAX_FIELD_ID = 0xFFFF;

    private Wire() {}

    /** A failure of encoding or decoding, as the errno a peer would be told. */
    public static final class Failure extends RuntimeException {
        public final int errno;

        public Failure(int errno, String message) {
            super(message);
            this.errno = errno;
        }
    }

    /** A body that does not decode (EBADMSG). */
    public static Failure malformed(String message) {
        return new Failure(Errno.EBADMSG, message);
    }

    /** {@code value}, or an encoding failure naming the field when it is null. */
    public static <T> T need(T value, String field) {
        if (value == null) throw new Failure(Errno.EINVAL, field + " is null");
        return value;
    }

    /** The method a server does not know (ENOSYS). */
    public static Failure unknownMethod(int method) {
        return new Failure(Errno.ENOSYS, "no method " + Integer.toUnsignedString(method));
    }

    /** The variant of {@code values} at a decoded index, or a decoding failure. */
    public static <E extends Enum<E>> E variant(E[] values, long index, String type) {
        if (index < 0 || index >= values.length) throw malformed(type + " has no variant " + index);
        return values[(int) index];
    }

    static String kindName(int kind) {
        switch (kind) {
            case BOOL: return "Bool";
            case I32: return "I32";
            case I64: return "I64";
            case U32: return "U32";
            case U64: return "U64";
            case F64: return "F64";
            case STRING: return "String";
            case BYTES: return "Bytes";
            case ARRAY: return "Array";
            case STRUCT: return "Struct";
            case MAP: return "Map";
            case OPTION: return "Option";
            case ERROR: return "Error";
            case HANDLE: return "Handle";
            case BUFFER: return "Buffer";
            default: return "kind " + kind;
        }
    }
}
