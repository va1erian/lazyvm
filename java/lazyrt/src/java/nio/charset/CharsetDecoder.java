package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/** Bytes to chars through decodeLoop, with the error actions and states of the Javadoc. */
public abstract class CharsetDecoder {
    static final int RESET = 0;
    static final int CODING = 1;
    static final int CODING_END = 2;
    static final int FLUSHED = 3;
    private static final String[] STATES = {"RESET", "CODING", "CODING_END", "FLUSHED"};

    private final Charset charset;
    private final float averageCharsPerByte;
    private final float maxCharsPerByte;
    private String replacement = "�";
    private CodingErrorAction malformed = CodingErrorAction.REPORT;
    private CodingErrorAction unmappable = CodingErrorAction.REPORT;
    private int state = RESET;

    protected CharsetDecoder(Charset cs, float averageCharsPerByte, float maxCharsPerByte) {
        if (averageCharsPerByte <= 0 || maxCharsPerByte <= 0) {
            throw new IllegalArgumentException("Non-positive chars per byte");
        }
        if (averageCharsPerByte > maxCharsPerByte) {
            throw new IllegalArgumentException("averageCharsPerByte exceeds maxCharsPerByte");
        }
        this.charset = cs;
        this.averageCharsPerByte = averageCharsPerByte;
        this.maxCharsPerByte = maxCharsPerByte;
    }

    public final Charset charset() {
        return charset;
    }

    public final String replacement() {
        return replacement;
    }

    // Spec K4: the checks of replaceWith, in this order.
    public final CharsetDecoder replaceWith(String newReplacement) {
        if (newReplacement == null) {
            throw new IllegalArgumentException("Null replacement");
        }
        if (newReplacement.isEmpty()) {
            throw new IllegalArgumentException("Empty replacement");
        }
        if (newReplacement.length() > maxCharsPerByte) {
            throw new IllegalArgumentException("Replacement too long");
        }
        replacement = newReplacement;
        implReplaceWith(newReplacement);
        return this;
    }

    protected void implReplaceWith(String newReplacement) {
    }

    public CodingErrorAction malformedInputAction() {
        return malformed;
    }

    public CodingErrorAction unmappableCharacterAction() {
        return unmappable;
    }

    public final CharsetDecoder onMalformedInput(CodingErrorAction newAction) {
        malformed = checkAction(newAction);
        implOnMalformedInput(newAction);
        return this;
    }

    public final CharsetDecoder onUnmappableCharacter(CodingErrorAction newAction) {
        unmappable = checkAction(newAction);
        implOnUnmappableCharacter(newAction);
        return this;
    }

    static CodingErrorAction checkAction(CodingErrorAction a) {
        if (a == null) {
            throw new IllegalArgumentException("Null action");
        }
        return a;
    }

    protected void implOnMalformedInput(CodingErrorAction newAction) {
    }

    protected void implOnUnmappableCharacter(CodingErrorAction newAction) {
    }

    public final float averageCharsPerByte() {
        return averageCharsPerByte;
    }

    public final float maxCharsPerByte() {
        return maxCharsPerByte;
    }

    // Spec K4: the message names both states.
    static IllegalStateException badState(int from, int to) {
        return new IllegalStateException("Current state = " + STATES[from] + ", new state = " + STATES[to]);
    }

    public final CoderResult decode(ByteBuffer in, CharBuffer out, boolean endOfInput) {
        int next = endOfInput ? CODING_END : CODING;
        if (state == FLUSHED || (state == CODING_END && !endOfInput)) {
            throw badState(state, next);
        }
        state = next;
        while (true) {
            CoderResult cr;
            try {
                cr = decodeLoop(in, out);
            } catch (RuntimeException x) {
                throw new CoderMalfunctionError(x);
            }
            if (cr.isOverflow()) {
                return cr;
            }
            if (cr.isUnderflow()) {
                if (!endOfInput || !in.hasRemaining()) {
                    return cr;
                }
                // What is left at the end is one malformed unit (Spec U5).
                cr = CoderResult.malformedForLength(in.remaining());
            }
            CodingErrorAction action = cr.isMalformed() ? malformed : unmappable;
            if (action == CodingErrorAction.REPORT) {
                return cr;
            }
            if (action == CodingErrorAction.REPLACE) {
                if (out.remaining() < replacement.length()) {
                    return CoderResult.OVERFLOW;
                }
                out.put(replacement);
            }
            in.position(in.position() + cr.length());
        }
    }

    public final CoderResult flush(CharBuffer out) {
        if (state == CODING_END) {
            CoderResult cr = implFlush(out);
            if (cr.isUnderflow()) {
                state = FLUSHED;
            }
            return cr;
        }
        if (state != FLUSHED) {
            throw badState(state, FLUSHED);
        }
        return CoderResult.UNDERFLOW;
    }

    protected CoderResult implFlush(CharBuffer out) {
        return CoderResult.UNDERFLOW;
    }

    public final CharsetDecoder reset() {
        implReset();
        state = RESET;
        return this;
    }

    protected void implReset() {
    }

    protected abstract CoderResult decodeLoop(ByteBuffer in, CharBuffer out);

    /** A whole decoding with this decoder's actions; the result is flipped. */
    public final CharBuffer decode(ByteBuffer in) throws CharacterCodingException {
        int n = (int) Math.min(Integer.MAX_VALUE - 8, (long) (in.remaining() * (double) averageCharsPerByte) + 1);
        CharBuffer out = CharBuffer.allocate(n);
        reset();
        boolean ending = false;
        while (true) {
            CoderResult cr = ending ? flush(out) : decode(in, out, true);
            if (cr.isUnderflow()) {
                if (ending) {
                    break;
                }
                ending = true;
                continue;
            }
            if (cr.isOverflow()) {
                out = grow(out);
                continue;
            }
            cr.throwException();
        }
        out.flip();
        return out;
    }

    static CharBuffer grow(CharBuffer out) {
        CharBuffer bigger = CharBuffer.allocate(out.capacity() * 2 + 16);
        out.flip();
        bigger.put(out);
        return bigger;
    }

    public boolean isAutoDetecting() {
        return false;
    }

    public boolean isCharsetDetected() {
        throw new UnsupportedOperationException();
    }

    public Charset detectedCharset() {
        throw new UnsupportedOperationException();
    }
}
