package java.nio.charset;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

/** Chars to bytes through encodeLoop, the mirror of CharsetDecoder. */
public abstract class CharsetEncoder {
    private final Charset charset;
    private final float averageBytesPerChar;
    private final float maxBytesPerChar;
    private byte[] replacement;
    private CodingErrorAction malformed = CodingErrorAction.REPORT;
    private CodingErrorAction unmappable = CodingErrorAction.REPORT;
    private int state = CharsetDecoder.RESET;

    protected CharsetEncoder(Charset cs, float averageBytesPerChar, float maxBytesPerChar, byte[] replacement) {
        // Observed: the messages name the parameter.
        if (averageBytesPerChar <= 0) {
            throw new IllegalArgumentException("Non-positive averageBytesPerChar");
        }
        if (maxBytesPerChar <= 0) {
            throw new IllegalArgumentException("Non-positive maxBytesPerChar");
        }
        if (averageBytesPerChar > maxBytesPerChar) {
            throw new IllegalArgumentException("averageBytesPerChar exceeds maxBytesPerChar");
        }
        this.charset = cs;
        this.averageBytesPerChar = averageBytesPerChar;
        this.maxBytesPerChar = maxBytesPerChar;
        replaceWith(replacement);
    }

    protected CharsetEncoder(Charset cs, float averageBytesPerChar, float maxBytesPerChar) {
        this(cs, averageBytesPerChar, maxBytesPerChar, new byte[] {'?'});
    }

    public final Charset charset() {
        return charset;
    }

    public final byte[] replacement() {
        return replacement.clone();
    }

    // Spec K4: the checks of replaceWith, in this order.
    public final CharsetEncoder replaceWith(byte[] newReplacement) {
        if (newReplacement == null) {
            throw new IllegalArgumentException("Null replacement");
        }
        if (newReplacement.length == 0) {
            throw new IllegalArgumentException("Empty replacement");
        }
        if (newReplacement.length > maxBytesPerChar) {
            throw new IllegalArgumentException("Replacement too long");
        }
        if (!isLegalReplacement(newReplacement)) {
            throw new IllegalArgumentException("Illegal replacement");
        }
        replacement = newReplacement.clone();
        implReplaceWith(replacement.clone());
        return this;
    }

    protected void implReplaceWith(byte[] newReplacement) {
    }

    /** Legal when the charset decodes it without an error (Javadoc). */
    public boolean isLegalReplacement(byte[] repl) {
        CharsetDecoder d = charset.newDecoder();
        CharBuffer out = CharBuffer.allocate((int) (repl.length * d.maxCharsPerByte()) + 1);
        CoderResult cr = d.decode(ByteBuffer.wrap(repl), out, true);
        return !cr.isError();
    }

    public CodingErrorAction malformedInputAction() {
        return malformed;
    }

    public CodingErrorAction unmappableCharacterAction() {
        return unmappable;
    }

    public final CharsetEncoder onMalformedInput(CodingErrorAction newAction) {
        malformed = CharsetDecoder.checkAction(newAction);
        implOnMalformedInput(newAction);
        return this;
    }

    public final CharsetEncoder onUnmappableCharacter(CodingErrorAction newAction) {
        unmappable = CharsetDecoder.checkAction(newAction);
        implOnUnmappableCharacter(newAction);
        return this;
    }

    protected void implOnMalformedInput(CodingErrorAction newAction) {
    }

    protected void implOnUnmappableCharacter(CodingErrorAction newAction) {
    }

    public final float averageBytesPerChar() {
        return averageBytesPerChar;
    }

    public final float maxBytesPerChar() {
        return maxBytesPerChar;
    }

    public final CoderResult encode(CharBuffer in, ByteBuffer out, boolean endOfInput) {
        int next = endOfInput ? CharsetDecoder.CODING_END : CharsetDecoder.CODING;
        if (state == CharsetDecoder.FLUSHED || (state == CharsetDecoder.CODING_END && !endOfInput)) {
            throw CharsetDecoder.badState(state, next);
        }
        state = next;
        while (true) {
            CoderResult cr;
            try {
                cr = encodeLoop(in, out);
            } catch (java.nio.BufferUnderflowException | java.nio.BufferOverflowException x) {
                // Observed: only these mean a broken coding loop; other exceptions pass through.
                throw new CoderMalfunctionError(x);
            }
            if (cr.isOverflow()) {
                return cr;
            }
            if (cr.isUnderflow()) {
                if (!endOfInput || !in.hasRemaining()) {
                    return cr;
                }
                cr = CoderResult.malformedForLength(in.remaining());
            }
            CodingErrorAction action = cr.isMalformed() ? malformed : unmappable;
            if (action == CodingErrorAction.REPORT) {
                return cr;
            }
            if (action == CodingErrorAction.REPLACE) {
                if (out.remaining() < replacement.length) {
                    return CoderResult.OVERFLOW;
                }
                out.put(replacement);
            }
            in.position(in.position() + cr.length());
        }
    }

    public final CoderResult flush(ByteBuffer out) {
        if (state == CharsetDecoder.CODING_END) {
            CoderResult cr = implFlush(out);
            if (cr.isUnderflow()) {
                state = CharsetDecoder.FLUSHED;
            }
            return cr;
        }
        if (state != CharsetDecoder.FLUSHED) {
            throw CharsetDecoder.badState(state, CharsetDecoder.FLUSHED);
        }
        return CoderResult.UNDERFLOW;
    }

    protected CoderResult implFlush(ByteBuffer out) {
        return CoderResult.UNDERFLOW;
    }

    public final CharsetEncoder reset() {
        implReset();
        state = CharsetDecoder.RESET;
        return this;
    }

    protected void implReset() {
    }

    protected abstract CoderResult encodeLoop(CharBuffer in, ByteBuffer out);

    /** A whole encoding with this encoder's actions; the result is flipped. */
    public final ByteBuffer encode(CharBuffer in) throws CharacterCodingException {
        int n = (int) Math.min(Integer.MAX_VALUE - 8, (long) (in.remaining() * (double) averageBytesPerChar) + 1);
        ByteBuffer out = ByteBuffer.allocate(n);
        reset();
        boolean ending = false;
        while (true) {
            CoderResult cr = ending ? flush(out) : encode(in, out, true);
            if (cr.isUnderflow()) {
                if (ending) {
                    break;
                }
                ending = true;
                continue;
            }
            if (cr.isOverflow()) {
                ByteBuffer bigger = ByteBuffer.allocate(out.capacity() * 2 + 16);
                out.flip();
                bigger.put(out);
                out = bigger;
                continue;
            }
            cr.throwException();
        }
        out.flip();
        return out;
    }

    public boolean canEncode(char c) {
        return canEncode(CharBuffer.wrap(new char[] {c}));
    }

    // Javadoc: a trial encoding with REPORT, on a fresh coder so this one keeps its state.
    public boolean canEncode(CharSequence cs) {
        CharsetEncoder e = charset.newEncoder();
        try {
            e.encode(CharBuffer.wrap(cs));
            return true;
        } catch (CharacterCodingException x) {
            return false;
        }
    }
}
