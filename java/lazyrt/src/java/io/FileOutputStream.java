package java.io;

import lazy.internal.FileSystem;

/** Bytes to a file through a lazy.internal.FileSystem handle (io.md FS1, FS2). */
public class FileOutputStream extends OutputStream {
    private final long handle;
    private final byte[] one = new byte[1];

    public FileOutputStream(String name, boolean append) throws FileNotFoundException {
        if (name == null) {
            throw new NullPointerException();
        }
        handle = FileSystem.open(File.openable(name), append ? FileSystem.APPEND : FileSystem.WRITE);
    }

    public FileOutputStream(String name) throws FileNotFoundException {
        this(name, false);
    }

    public FileOutputStream(File file, boolean append) throws FileNotFoundException {
        this(file.getPath(), append);
    }

    public FileOutputStream(File file) throws FileNotFoundException {
        this(file.getPath(), false);
    }

    public void write(int b) throws IOException {
        one[0] = (byte) b;
        FileSystem.write(handle, one, 0, 1);
    }

    public void write(byte[] b) throws IOException {
        FileSystem.write(handle, b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        FileSystem.write(handle, b, off, len);
    }

    public void close() throws IOException {
        FileSystem.close(handle);
    }
}
