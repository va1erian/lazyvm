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
    /** Encoded bytes not yet written: `out` sees whole blocks of LIMIT bytes, as on the oracle. */
    private final byte[] block = new byte[LIMIT];
    private int used;
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
        if (off < 0 || len < 0 || off > cbuf.length - len) {
            throw new IndexOutOfBoundsException();
        }
        pending.append(cbuf, off, len);
        if (pending.length() >= LIMIT) {
            drain(false);
        }
    }

    public void write(String str, int off, int len) throws IOException {
        ensureOpen();
        if (str == null) {
            throw new NullPointerException();
        }
        // Observed on the oracle: an empty or overflowing range writes
        // nothing; any other bad range names its out-of-range end.
        int end = off + len;
        if (end <= off) {
            return;
        }
        if (off < 0) {
            throw new StringIndexOutOfBoundsException(off);
        }
        if (end > str.length()) {
            throw new StringIndexOutOfBoundsException(end);
        }
        pending.append(str, off, end);
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
        for (int i = 0; i < bytes.length;) {
            int k = Math.min(LIMIT - used, bytes.length - i);
            // Observed on the oracle: a char's bytes never straddle two
            // blocks; a block ends short instead (tests/java/util/WriterBlocks).
            while (i + k < bytes.length && (bytes[i + k] & 0xC0) == 0x80) {
                k--;
            }
            System.arraycopy(bytes, i, block, used, k);
            used += k;
            i += k;
            if (i < bytes.length) {
                writeBlock();
            }
        }
        if (used == LIMIT) {
            writeBlock();
        }
    }

    private void writeBlock() throws IOException {
        // A block that fails to write is dropped, not resent, as on the oracle.
        int n = used;
        used = 0;
        if (n > 0) {
            out.write(block, 0, n);
        }
    }

    public void flush() throws IOException {
        ensureOpen();
        drain(false);
        writeBlock();
        out.flush();
    }

    public void close() throws IOException {
        if (!closed) {
            ensureOpen();
            // Closing does not flush `out` first, and a failed close stays
            // closed, as observed on the oracle.
            try {
                drain(true);
                writeBlock();
                out.close();
            } finally {
                closed = true;
            }
        }
    }
}
