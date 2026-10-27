package java.io;

/**
 * Encodes chars to UTF-8 bytes on an OutputStream. Text stays buffered until
 * flush(), like a PrintWriter over a stream; the real OutputStreamWriter is
 * S5-05 and will replace this.
 */
final class StreamWriter extends Writer {
    private static final int LIMIT = 8192;

    private final OutputStream out;
    private final StringBuilder pending = new StringBuilder();
    private boolean closed;
    /** Bytes reached `out` since its last flush; close flushes only then. */
    private boolean unflushed;

    StreamWriter(OutputStream out) {
        super(out);
        this.out = out;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
    }

    public void write(char[] cbuf, int off, int len) throws IOException {
        ensureOpen();
        pending.append(cbuf, off, len);
        if (pending.length() >= LIMIT) {
            drain(false);
        }
    }

    public void write(String str, int off, int len) throws IOException {
        ensureOpen();
        pending.append(str, off, off + len);
        if (pending.length() >= LIMIT) {
            drain(false);
        }
    }

    /**
     * Encodes what is pending. Unless `all`, a trailing high surrogate waits
     * for its low half, so a pair split across writes stays one code point.
     */
    private void drain(boolean all) throws IOException {
        int n = pending.length();
        if (!all && n > 0 && Character.isHighSurrogate(pending.charAt(n - 1))) {
            n--;
        }
        if (n == 0) {
            return;
        }
        byte[] bytes = pending.substring(0, n).getBytes();
        pending.delete(0, n);
        out.write(bytes, 0, bytes.length);
        unflushed = true;
    }

    public void flush() throws IOException {
        ensureOpen();
        drain(false);
        out.flush();
        unflushed = false;
    }

    public void close() throws IOException {
        if (!closed) {
            ensureOpen();
            drain(true);
            if (unflushed) {
                out.flush();
            }
            closed = true;
            out.close();
        }
    }
}
