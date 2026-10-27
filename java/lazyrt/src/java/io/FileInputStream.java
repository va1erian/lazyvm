package java.io;

import lazy.internal.FileSystem;

/** Bytes of a file through a lazy.internal.FileSystem handle (io.md FS1-FS3). */
public class FileInputStream extends InputStream {
    private final long handle;
    private final byte[] one = new byte[1];

    public FileInputStream(String name) throws FileNotFoundException {
        if (name == null) {
            throw new NullPointerException();
        }
        handle = FileSystem.open(name, FileSystem.READ);
    }

    public FileInputStream(File file) throws FileNotFoundException {
        this(file.getPath());
    }

    public int read() throws IOException {
        return FileSystem.read(handle, one, 0, 1) < 0 ? -1 : one[0] & 0xff;
    }

    public int read(byte[] b) throws IOException {
        return FileSystem.read(handle, b, 0, b.length);
    }

    public int read(byte[] b, int off, int len) throws IOException {
        return FileSystem.read(handle, b, off, len);
    }

    // Spec FS3: moves by n even past the end; back for a negative n, never below 0.
    public long skip(long n) throws IOException {
        long pos = FileSystem.position(handle);
        FileSystem.seek(handle, pos + n);
        return n;
    }

    public int available() throws IOException {
        return FileSystem.available(handle);
    }

    public void close() throws IOException {
        FileSystem.close(handle);
    }
}
