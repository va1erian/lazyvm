package lazy.internal;

import java.io.IOException;
import java.io.OutputStream;

/** An OutputStream over a process file descriptor (1 = stdout, 2 = stderr). */
public final class ConsoleOutputStream extends OutputStream {
    private final int fd;

    public ConsoleOutputStream(int fd) {
        this.fd = fd;
    }

    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len > 0) {
            write0(fd, b, off, len);
        }
    }

    public void flush() throws IOException {
        flush0(fd);
    }

    private static native void write0(int fd, byte[] b, int off, int len);

    private static native void flush0(int fd);
}
