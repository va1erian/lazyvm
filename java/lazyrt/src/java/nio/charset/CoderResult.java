package java.nio.charset;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.util.HashMap;
import java.util.Map;

public class CoderResult {
    private static final int UNDER = 0;
    private static final int OVER = 1;
    private static final int MALFORMED = 2;
    private static final int UNMAPPABLE = 3;

    public static final CoderResult UNDERFLOW = new CoderResult(UNDER, 0);
    public static final CoderResult OVERFLOW = new CoderResult(OVER, 0);

    // Spec K5: equal lengths give the same object.
    private static final Map<Integer, CoderResult> MALFORMED_CACHE = new HashMap<>();
    private static final Map<Integer, CoderResult> UNMAPPABLE_CACHE = new HashMap<>();

    private final int kind;
    private final int length;

    private CoderResult(int kind, int length) {
        this.kind = kind;
        this.length = length;
    }

    /** The results of length 1 to 4, which the standard charsets give, without a lookup. */
    private static final CoderResult[] SHORT_MALFORMED = shortOnes(MALFORMED);
    private static final CoderResult[] SHORT_UNMAPPABLE = shortOnes(UNMAPPABLE);

    private static CoderResult[] shortOnes(int kind) {
        CoderResult[] r = new CoderResult[5];
        for (int i = 1; i < r.length; i++) {
            r[i] = new CoderResult(kind, i);
        }
        return r;
    }

    public static CoderResult malformedForLength(int length) {
        if (length > 0 && length < SHORT_MALFORMED.length) {
            return SHORT_MALFORMED[length];
        }
        return cached(MALFORMED_CACHE, MALFORMED, length);
    }

    public static CoderResult unmappableForLength(int length) {
        if (length > 0 && length < SHORT_UNMAPPABLE.length) {
            return SHORT_UNMAPPABLE[length];
        }
        return cached(UNMAPPABLE_CACHE, UNMAPPABLE, length);
    }

    private static synchronized CoderResult cached(Map<Integer, CoderResult> cache, int kind, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Non-positive length");
        }
        CoderResult r = cache.get(length);
        if (r == null) {
            r = new CoderResult(kind, length);
            cache.put(length, r);
        }
        return r;
    }

    public boolean isUnderflow() {
        return kind == UNDER;
    }

    public boolean isOverflow() {
        return kind == OVER;
    }

    public boolean isError() {
        return kind >= MALFORMED;
    }

    public boolean isMalformed() {
        return kind == MALFORMED;
    }

    public boolean isUnmappable() {
        return kind == UNMAPPABLE;
    }

    public int length() {
        if (!isError()) {
            throw new UnsupportedOperationException();
        }
        return length;
    }

    public void throwException() throws CharacterCodingException {
        switch (kind) {
            case UNDER:
                throw new BufferUnderflowException();
            case OVER:
                throw new BufferOverflowException();
            case MALFORMED:
                throw new MalformedInputException(length);
            default:
                throw new UnmappableCharacterException(length);
        }
    }

    public String toString() {
        switch (kind) {
            case UNDER:
                return "UNDERFLOW";
            case OVER:
                return "OVERFLOW";
            case MALFORMED:
                return "MALFORMED[" + length + "]";
            default:
                return "UNMAPPABLE[" + length + "]";
        }
    }
}
