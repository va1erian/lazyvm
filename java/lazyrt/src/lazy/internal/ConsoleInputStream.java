package lazy.internal;

import java.io.IOException;
import java.io.InputStream;

/** An InputStream over a process file descriptor (0 = stdin). */
public final class ConsoleInputStream extends InputStream {
    private final int fd;

    public ConsoleInputStream(int fd) {
        this.fd = fd;
    }

    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n <= 0 ? -1 : (one[0] & 0xff);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }
        return read0(fd, b, off, len);
    }

    public int available() throws IOException {
        return available0(fd);
    }

    private static native int read0(int fd, byte[] b, int off, int len);

    private static native int available0(int fd);
}
