package lazy.internal;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * The file natives (docs/design/files.md): open files as numbered handles,
 * and operations on paths. Paths are the strings Java code built; relative
 * ones resolve against the current directory, which is `user.dir`.
 */
public final class FileSystem {
    public static final int READ = 0;
    public static final int WRITE = 1;
    public static final int APPEND = 2;
    public static final int READ_WRITE = 3;
    public static final int READ_WRITE_EXISTING = 4;

    public static final int EXISTS = 1;
    public static final int REGULAR = 2;
    public static final int DIRECTORY = 4;
    public static final int LINK = 8;
    public static final int READABLE = 16;
    public static final int WRITABLE = 32;
    public static final int EXECUTABLE = 64;
    public static final int HIDDEN = 128;

    private FileSystem() {
    }

    public static native long open(String path, int mode) throws FileNotFoundException;

    public static native int read(long h, byte[] b, int off, int len) throws IOException;

    public static native void write(long h, byte[] b, int off, int len) throws IOException;

    public static native long position(long h) throws IOException;

    public static native void seek(long h, long pos) throws IOException;

    public static native long size(long h) throws IOException;

    public static native void truncate(long h, long len) throws IOException;

    public static native int available(long h) throws IOException;

    public static native void sync(long h) throws IOException;

    public static native void close(long h);

    public static native int attributes(String path);

    public static native long length(String path);

    public static native long lastModified(String path);

    public static native boolean setLastModified(String path, long millis);

    public static native String[] list(String path);

    public static native boolean delete(String path);

    public static native boolean rename(String from, String to);

    public static native boolean mkdir(String path);

    public static native boolean createNew(String path) throws IOException;

    public static native String canonicalize(String path) throws IOException;

    public static native boolean setReadOnly(String path);

    /** Grants or takes away read (4), write (2) or execute (1) access, for the owner or all. */
    public static native boolean setAccess(String path, int access, boolean enable, boolean ownerOnly);

    public static native String tempDir();
}
