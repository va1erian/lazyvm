package lazy.internal.msg;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import lazy.msg.Buffer;

/**
 * Reads one Messenger body field by field. {@link #next} moves to the next field; the typed
 * getters read the current one and fail with EBADMSG when its kind or size is not what the
 * declaration says. Unknown field ids are the caller's to skip, which is what makes appending
 * fields compatible (LazyOS docs/midl.md, "Wire encoding").
 */
public final class WireReader {
    private final byte[] bytes;
    private final int end;
    private final List<Object> objects;
    /** The next object index a field may claim, shared by the readers of one body. */
    private final int[] nextObject;
    /** How deep this reader is in nested containers; past MAX_DEPTH a body is refused, not recursed. */
    private final int depth;
    private int pos;
    private int kind, id, start, size;
    private static final int MAX_DEPTH = 64;

    public WireReader(byte[] bytes) {
        this(bytes, 0, bytes.length, Collections.emptyList(), new int[1], 0);
    }

    public WireReader(byte[] bytes, List<Object> objects) {
        this(bytes, 0, bytes.length, objects, new int[1], 0);
    }

    private WireReader(byte[] bytes, int from, int to, List<Object> objects, int[] nextObject, int depth) {
        if (depth > MAX_DEPTH) throw Wire.malformed("containers nested deeper than " + MAX_DEPTH);
        this.depth = depth;
        this.bytes = bytes;
        this.pos = from;
        this.end = to;
        this.objects = objects;
        this.nextObject = nextObject;
    }

    /**
     * Moves to the next field; false at the end of the body. A field of a kind this reader does
     * not know is skipped, and the id is the tag's 16 bits, as libmessenger reads them.
     */
    public boolean next() {
        for (;;) {
            if (pos == end) return false;
            if (end - pos < 8) throw Wire.malformed("truncated field header");
            int tag = (int) le(pos, 4);
            long length = le(pos + 4, 4);
            if (length > end - pos - 8) throw Wire.malformed("field longer than its body");
            kind = tag & 0xFF;
            id = (tag >>> 8) & 0xFFFF;
            start = pos + 8;
            size = (int) length;
            pos = start + size;
            if (kind >= Wire.BOOL && kind <= Wire.BUFFER) return true;
        }
    }

    /** The current field's id. */
    public int id() {
        return id;
    }

    /** The current field's kind ({@code Wire.BOOL} ...). */
    public int kind() {
        return kind;
    }

    public boolean bool() {
        want(Wire.BOOL, 1);
        return bytes[start] != 0;
    }

    public int i32() {
        want(Wire.I32, 4);
        return (int) le(start, 4);
    }

    public long i64() {
        want(Wire.I64, 8);
        return le(start, 8);
    }

    /** A {@code U32}, widened to a non-negative long. */
    public long u32() {
        want(Wire.U32, 4);
        return le(start, 4);
    }

    /** A {@code U64}: its 64 bits, to be read as unsigned. */
    public long u64() {
        want(Wire.U64, 8);
        return le(start, 8);
    }

    public double f64() {
        want(Wire.F64, 8);
        return Double.longBitsToDouble(le(start, 8));
    }

    /** A {@code String}; bytes that are not UTF-8 are a malformed body. */
    public String string() {
        want(Wire.STRING, -1);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, size)).toString();
        } catch (CharacterCodingException e) {
            throw Wire.malformed("field " + id + " is not UTF-8");
        }
    }

    public byte[] bytes() {
        want(Wire.BYTES, -1);
        byte[] copy = new byte[size];
        System.arraycopy(bytes, start, copy, 0, size);
        return copy;
    }

    /** An enum's variant: its index in {@code values}. */
    public <E extends Enum<E>> E variant(E[] values, String type) {
        return Wire.variant(values, u32(), type);
    }

    /** The payload of a container field ({@code Array}, {@code Option}, {@code Struct}) as a reader. */
    public WireReader open(int kind) {
        want(kind, -1);
        return new WireReader(bytes, start, start + size, objects, nextObject, depth + 1);
    }

    /** An {@code Array}: each element field read by {@code item}. */
    public <T> List<T> list(Function<WireReader, T> item) {
        WireReader items = open(Wire.ARRAY);
        List<T> out = new ArrayList<>();
        while (items.next()) out.add(item.apply(items));
        return out;
    }

    /** An {@code Option}: null when empty, else its one field read by {@code item}. */
    public <T> T option(Function<WireReader, T> item) {
        WireReader inner = open(Wire.OPTION);
        return inner.next() ? item.apply(inner) : null;
    }

    /**
     * The channel end the current field names, turned into what the program sees by {@code
     * make}. Objects are claimed in the order they are declared, so each index must be the next.
     */
    public <T> T handle(Function<Object, T> make) {
        want(Wire.HANDLE, 4);
        return make.apply(object(le(start, 4)));
    }

    /** The shared buffer the current field names, with its offset and length. */
    public Buffer buffer() {
        want(Wire.BUFFER, 20);
        Object o = object(le(start, 4));
        if (!(o instanceof Buffer)) throw Wire.malformed("object " + (nextObject[0] - 1) + " is not a buffer");
        long offset = le(start + 4, 8), length = le(start + 12, 8);
        try {
            return ((Buffer) o).range(offset, length);
        } catch (IllegalArgumentException e) {
            throw Wire.malformed(e.getMessage());
        }
    }

    /** The standard error of a reply body, or null when it has none. */
    public static Fault fault(byte[] body) {
        WireReader r = new WireReader(body);
        while (r.next()) {
            if (r.id == Wire.ERROR_FIELD && r.kind == Wire.ERROR) return r.faultParts();
        }
        return null;
    }

    private Fault faultParts() {
        if (size < 4) throw Wire.malformed("error field too short");
        int code = (int) le(start, 4);
        int textEnd = start + 4;
        while (textEnd < start + size && bytes[textEnd] != 0) textEnd++;
        String sentence = new String(bytes, start + 4, textEnd - start - 4, StandardCharsets.UTF_8);
        String domain = "", hint = null, docs = null;
        if (textEnd < start + size) {
            WireReader detail = new WireReader(bytes, textEnd + 1, start + size, objects, nextObject, depth + 1);
            while (detail.next()) {
                if (detail.id == 1) domain = detail.string();
                else if (detail.id == 2) hint = detail.string();
                else if (detail.id == 3) docs = detail.string();
            }
        }
        return new Fault(code, sentence, domain, hint, docs);
    }

    private Object object(long index) {
        int expected = nextObject[0];
        if (index != expected || index >= objects.size()) {
            throw Wire.malformed("object index " + index + ", expected " + expected);
        }
        nextObject[0]++;
        return objects.get((int) index);
    }

    private void want(int wanted, int fixedSize) {
        if (kind != wanted) {
            throw Wire.malformed("field " + id + " is " + Wire.kindName(kind) + ", expected " + Wire.kindName(wanted));
        }
        if (fixedSize >= 0 && size != fixedSize) throw Wire.malformed("field " + id + " has " + size + " bytes");
    }

    private long le(int at, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value |= (bytes[at + i] & 0xFFL) << (8 * i);
        return value;
    }
}
