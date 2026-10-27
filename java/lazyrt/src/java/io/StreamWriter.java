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
            drain();
        }
    }

    public void write(String str, int off, int len) throws IOException {
        ensureOpen();
        pending.append(str, off, off + len);
        if (pending.length() >= LIMIT) {
            drain();
        }
    }

    private void drain() throws IOException {
        byte[] bytes = pending.toString().getBytes();
        pending.setLength(0);
        out.write(bytes, 0, bytes.length);
    }

    public void flush() throws IOException {
        ensureOpen();
        drain();
        out.flush();
    }

    public void close() throws IOException {
        if (!closed) {
            flush();
            closed = true;
            out.close();
        }
    }
}
