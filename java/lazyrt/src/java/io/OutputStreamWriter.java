package java.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import lazy.internal.Encodings;

/**
 * Chars to bytes through a CharsetEncoder into an 8192-byte buffer, which
 * goes out when the next char does not fit (io.md R3). A high surrogate
 * that ends a write waits in `held` for the next one.
 */
public class OutputStreamWriter extends Writer {
    private final OutputStream out;
    private final CharsetEncoder encoder;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private int held = -1;
    private boolean closed;

    public OutputStreamWriter(OutputStream out, String charsetName) throws UnsupportedEncodingException {
        this(out, Encodings.forName(charsetName));
    }

    public OutputStreamWriter(OutputStream out) {
        this(out, Charset.defaultCharset());
    }

    public OutputStreamWriter(OutputStream out, Charset cs) {
        this(out, cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    // Spec R3: a writer built from an encoder keeps its actions.
    public OutputStreamWriter(OutputStream out, CharsetEncoder enc) {
        super(out);
        if (enc == null) {
            throw new NullPointerException("charset encoder");
        }
        this.out = out;
        this.encoder = enc;
    }

    public String getEncoding() {
        return closed ? null : Encodings.historicalName(encoder.charset());
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            // Observed: the closed state is checked before the bounds.
            ensureOpen();
            if (off < 0 || len < 0 || off > cbuf.length - len) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return;
            }
            CharBuffer in;
            if (held >= 0) {
                char[] joined = new char[len + 1];
                joined[0] = (char) held;
                System.arraycopy(cbuf, off, joined, 1, len);
                held = -1;
                in = CharBuffer.wrap(joined);
            } else {
                in = CharBuffer.wrap(cbuf, off, len);
            }
            encode(in, false);
            if (in.hasRemaining()) {
                held = in.get();
            }
        }
    }

    // Observed: a negative len is IndexOutOfBoundsException; the chars are then taken with
    // String.getChars (its own exceptions for a bad range), before the closed state is checked.
    public void write(String str, int off, int len) throws IOException {
        if (len < 0) {
            throw new IndexOutOfBoundsException();
        }
        char[] chars = new char[len];
        str.getChars(off, off + len, chars, 0);
        write(chars, 0, len);
    }

    /** Encodes `in`, writing the buffer out whenever the next char does not fit. */
    private void encode(CharBuffer in, boolean endOfInput) throws IOException {
        while (true) {
            CoderResult cr = encoder.encode(in, bytes, endOfInput);
            if (cr.isOverflow()) {
                writeBytes();
            } else if (cr.isUnderflow()) {
                return;
            } else {
                cr.throwException();
            }
        }
    }

    /** A buffer that fails to write is dropped, not resent, as on the oracle (S5-03). */
    private void writeBytes() throws IOException {
        int n = bytes.position();
        bytes.clear();
        if (n > 0) {
            out.write(bytes.array(), 0, n);
        }
    }

    public void flush() throws IOException {
        synchronized (lock) {
            ensureOpen();
            writeBytes();
            out.flush();
        }
    }

    // Spec R3: a held surrogate becomes the replacement; no flush of the stream; only once.
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            try {
                CharBuffer rest = held >= 0 ? CharBuffer.wrap(new char[] {(char) held}) : CharBuffer.allocate(0);
                held = -1;
                encode(rest, true);
                while (encoder.flush(bytes).isOverflow()) {
                    writeBytes();
                }
                writeBytes();
                out.close();
            } finally {
                closed = true;
            }
        }
    }
}
