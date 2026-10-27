package java.io;

public abstract class InputStream implements Closeable {
    public InputStream() {
    }

    public abstract int read() throws IOException;

    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        int c = read();
        if (c == -1) {
            return -1;
        }
        b[off] = (byte) c;
        int i = 1;
        try {
            for (; i < len; i++) {
                c = read();
                if (c == -1) {
                    break;
                }
                b[off + i] = (byte) c;
            }
        } catch (IOException ignored) {
            // Bytes already read are returned; the error shows up on the next read.
        }
        return i;
    }

    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        byte[] buf = new byte[(int) Math.min(2048L, n)];
        long remaining = n;
        while (remaining > 0) {
            int r = read(buf, 0, (int) Math.min((long) buf.length, remaining));
            if (r < 0) {
                break;
            }
            remaining -= r;
        }
        return n - remaining;
    }

    public int available() throws IOException {
        return 0;
    }

    public void close() throws IOException {
    }

    public synchronized void mark(int readlimit) {
    }

    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    public boolean markSupported() {
        return false;
    }
}
