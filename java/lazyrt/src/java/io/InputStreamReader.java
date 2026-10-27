package java.io;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import lazy.internal.Encodings;

/**
 * Bytes to chars through a CharsetDecoder, reading the stream 8192 bytes at
 * a time (io.md R1, R2). Bytes of a sequence split between reads wait in
 * `bytes`; the second half of a pair that did not fit waits in `spare`.
 */
public class InputStreamReader extends Reader {
    private final InputStream in;
    private final CharsetDecoder decoder;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private int spare = -1;
    private boolean ended;
    /** The decoder was flushed at the end: nothing more can come until new bytes do. */
    private boolean drained;
    private boolean closed;

    public InputStreamReader(InputStream in) {
        this(in, Charset.defaultCharset());
    }

    public InputStreamReader(InputStream in, String charsetName) throws UnsupportedEncodingException {
        this(in, Encodings.forName(charsetName));
    }

    public InputStreamReader(InputStream in, Charset cs) {
        this(in, cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE));
    }

    // Spec R2: a reader built from a decoder keeps its actions.
    public InputStreamReader(InputStream in, CharsetDecoder dec) {
        super(in);
        if (dec == null) {
            throw new NullPointerException("charset decoder");
        }
        this.in = in;
        this.decoder = dec;
        bytes.flip();
    }

    public String getEncoding() {
        return closed ? null : Encodings.historicalName(decoder.charset());
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    /** Reads more bytes after those left over; false at the end of the stream. */
    private boolean readMore() throws IOException {
        bytes.compact();
        int n;
        try {
            n = in.read(bytes.array(), bytes.position(), bytes.remaining());
        } finally {
            bytes.flip();
        }
        if (n < 0) {
            return false;
        }
        if (n == 0) {
            // Observed: a stream that reads nothing while asked for bytes is an error, not a retry.
            throw new IOException("Underlying input stream returned zero bytes");
        }
        bytes.limit(bytes.limit() + n);
        if (ended && n > 0) {
            // More bytes after an end: a new decoding run.
            decoder.reset();
            ended = false;
            drained = false;
        }
        return true;
    }

    // Observed: a char already decoded is returned by read() even after close (not by read(char[])).
    public int read() throws IOException {
        synchronized (lock) {
            if (spare >= 0) {
                int c = spare;
                spare = -1;
                return c;
            }
            return super.read();
        }
    }

    public int read(char[] cbuf, int off, int len) throws IOException {
        synchronized (lock) {
            ensureOpen();
            if (off < 0 || len < 0 || len > cbuf.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }
            int got = 0;
            if (spare >= 0) {
                // Observed: after a waiting char, more comes only if bytes are buffered or ready.
                cbuf[off++] = (char) spare;
                spare = -1;
                len--;
                got = 1;
                if (len == 0 || closed || !(bytes.hasRemaining() || in.available() > 0)) {
                    return got;
                }
            }
            int n = len == 1 ? single(cbuf, off) : fill(CharBuffer.wrap(cbuf, off, len), off);
            return n < 0 ? (got == 0 ? -1 : got) : got + n;
        }
    }

    /**
     * One char: decoded through two, so a pair can come out (the second half
     * waits in `spare`). Observed: an error after the first char still throws.
     */
    private int single(char[] cbuf, int off) throws IOException {
        CharBuffer two = CharBuffer.allocate(2);
        int n = fill(two, 0);
        if (n > 0) {
            cbuf[off] = two.get(0);
            if (n > 1) {
                spare = two.get(1);
            }
            return 1;
        }
        return n;
    }

    /**
     * Spec R1: decodes until `out` is full, or the bytes run out and the
     * stream has none ready. Observed: a coding error throws at once, even
     * after chars were produced.
     */
    private int fill(CharBuffer out, int off) throws IOException {
        while (out.hasRemaining()) {
            // After the end only a new byte (readMore) restarts decoding.
            CoderResult cr = ended ? CoderResult.UNDERFLOW : decoder.decode(bytes, out, false);
            if (cr.isError()) {
                cr.throwException();
            }
            // Observed: a full buffer returns at once, before the stream is asked anything.
            if (cr.isOverflow() || !out.hasRemaining()) {
                break;
            }
            if (out.position() > off && in.available() <= 0) {
                break;
            }
            if (!readMore()) {
                return finish(out, off);
            }
        }
        return out.position() - off;
    }

    /** The stream ended: what is left is decoded as an end (Spec U5), -1 if nothing came. */
    private int finish(CharBuffer out, int off) throws IOException {
        ended = true;
        if (!drained) {
            // Observed: the decoder starts afresh for what is left at the end, so UTF-16
            // reads a dangling unit big-endian even after a little-endian mark.
            decoder.reset();
            CoderResult cr = decoder.decode(bytes, out, true);
            if (cr.isUnderflow()) {
                cr = decoder.flush(out);
                drained = cr.isUnderflow();
            }
            if (cr.isError()) {
                cr.throwException();
            }
        }
        int n = out.position() - off;
        return n == 0 ? -1 : n;
    }

    // Spec R2: chars or bytes left, else whether the stream has some.
    public boolean ready() throws IOException {
        synchronized (lock) {
            ensureOpen();
            return spare >= 0 || bytes.hasRemaining() || in.available() > 0;
        }
    }

    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            in.close();
        }
    }
}
