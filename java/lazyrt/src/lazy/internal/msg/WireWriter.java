package lazy.internal.msg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lazy.msg.Buffer;

/**
 * Writes one Messenger body. A container field ({@code Array}, {@code Option}, {@code Struct}) is
 * written in place: {@link #begin} leaves its length open and {@link #end} fills it in. The kernel
 * objects a request carries are collected in {@link #objects}, and an object field holds its index
 * there (LazyOS docs/midl.md, "Objects").
 */
public final class WireWriter {
    private byte[] bytes = new byte[64];
    private int length;
    private final List<Object> objects = new ArrayList<>();

    public WireWriter bool(int id, boolean value) {
        header(Wire.BOOL, id, 1);
        put(value ? 1 : 0);
        return this;
    }

    public WireWriter i32(int id, int value) {
        header(Wire.I32, id, 4);
        le(value, 4);
        return this;
    }

    public WireWriter i64(int id, long value) {
        header(Wire.I64, id, 8);
        le(value, 8);
        return this;
    }

    /** A {@code U32}: {@code value} must lie in 0 to 2^32 - 1. */
    public WireWriter u32(int id, long value) {
        if (value < 0 || value > 0xFFFFFFFFL) throw new Wire.Failure(Errno.EINVAL, "U32 out of range: " + value);
        header(Wire.U32, id, 4);
        le(value, 4);
        return this;
    }

    /** A {@code U64}: the 64 bits of {@code value}, read as unsigned. */
    public WireWriter u64(int id, long value) {
        header(Wire.U64, id, 8);
        le(value, 8);
        return this;
    }

    public WireWriter f64(int id, double value) {
        header(Wire.F64, id, 8);
        le(Double.doubleToRawLongBits(value), 8);
        return this;
    }

    public WireWriter string(int id, String value) {
        byte[] utf8 = Wire.need(value, "String").getBytes(StandardCharsets.UTF_8);
        header(Wire.STRING, id, utf8.length);
        put(utf8, 0, utf8.length);
        return this;
    }

    public WireWriter bytes(int id, byte[] value) {
        Wire.need(value, "Bytes");
        header(Wire.BYTES, id, value.length);
        put(value, 0, value.length);
        return this;
    }

    /** An enum, as its variant's index. */
    public WireWriter variant(int id, Enum<?> value) {
        return u32(id, Wire.need(value, "enum").ordinal());
    }

    /** Opens a container field; its payload is what is written until {@link #end}. */
    public int begin(int kind, int id) {
        header(kind, id, 0);
        return length;
    }

    /** Closes the container that {@link #begin} returned {@code mark} for. */
    public void end(int mark) {
        int size = length - mark;
        int at = mark - 4;
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (size >>> (8 * i));
    }

    /** A channel end: {@code end} goes into the object list and the field holds its index. */
    public WireWriter handle(int id, Object end) {
        header(Wire.HANDLE, id, 4);
        le(objects.size(), 4);
        objects.add(Wire.need(end, "Channel"));
        return this;
    }

    /** A shared buffer: its index in the object list, then its offset and length. */
    public WireWriter buffer(int id, Buffer buffer) {
        Wire.need(buffer, "Buffer");
        header(Wire.BUFFER, id, 20);
        le(objects.size(), 4);
        le(buffer.offset(), 8);
        le(buffer.length(), 8);
        objects.add(buffer);
        return this;
    }

    /**
     * The standard error: code, the sentence, then, when any is given, a NUL and the detail
     * record (domain, hint, docs; LazyOS docs/midl.md, "Errors").
     */
    public WireWriter error(int code, String sentence, String domain, String hint, String docs) {
        int mark = begin(Wire.ERROR, Wire.ERROR_FIELD);
        le(code, 4);
        // The sentence ends at a NUL on the wire, so one inside it would be read as the detail record.
        String plain = sentence == null ? "" : sentence;
        int nul = plain.indexOf('\0');
        byte[] text = (nul < 0 ? plain : plain.substring(0, nul)).getBytes(StandardCharsets.UTF_8);
        put(text, 0, text.length);
        boolean hasDomain = domain != null && !domain.isEmpty();
        if (hasDomain || hint != null || docs != null) {
            put(0);
            if (hasDomain) string(1, domain);
            if (hint != null) string(2, hint);
            if (docs != null) string(3, docs);
        }
        end(mark);
        return this;
    }

    /** The body written so far. */
    public byte[] toBytes() {
        return Arrays.copyOf(bytes, length);
    }

    /** The kernel objects the body names, in index order. */
    public List<Object> objects() {
        return objects;
    }

    private void header(int kind, int id, int size) {
        if (id < 0 || id > Wire.MAX_FIELD_ID) throw new Wire.Failure(Errno.EINVAL, "field id " + id);
        le(kind | (id << 8), 4);
        le(size, 4);
    }

    private void le(long value, int count) {
        room(count);
        for (int i = 0; i < count; i++) bytes[length++] = (byte) (value >>> (8 * i));
    }

    private void put(int b) {
        room(1);
        bytes[length++] = (byte) b;
    }

    private void put(byte[] src, int from, int count) {
        room(count);
        System.arraycopy(src, from, bytes, length, count);
        length += count;
    }

    private void room(int more) {
        if (more > Integer.MAX_VALUE - length) throw new Wire.Failure(Errno.EMSGSIZE, "body too large");
        if (length + more > bytes.length) {
            int grown = (int) Math.min(Integer.MAX_VALUE, Math.max((long) bytes.length * 2, (long) length + more));
            bytes = Arrays.copyOf(bytes, grown);
        }
    }
}
