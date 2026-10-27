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
    /** Added to a mode: others may rename or delete the file while it is open. */
    public static final int SHARE_DELETE = 0x10;

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

    public static final int MODIFIED = 0;
    public static final int ACCESSED = 1;
    public static final int CREATED = 2;

    /** A time stamp in microseconds from the epoch, Long.MIN_VALUE when missing or not kept. */
    public static native long fileTime(String path, int which);

    /** Sets a time stamp; the IOException's message is the OS's text. */
    public static native void setFileTime(String path, int which, long micros) throws IOException;

    public static native String[] list(String path);

    /**
     * Deletes a file or an empty directory. A failure is NoSuchFileException,
     * AccessDeniedException or DirectoryNotEmptyException with the path, or an
     * IOException with the OS's text.
     */
    public static native void delete(String path) throws IOException;

    public static native boolean rename(String from, String to);

    /**
     * Renames, replacing a file in the way: false when the target is on another
     * volume (copy instead). A failure is thrown as delete's are.
     */
    public static native boolean move(String from, String to) throws IOException;

    public static native boolean mkdir(String path);

    public static native boolean createNew(String path) throws IOException;

    public static native String canonicalize(String path) throws IOException;

    /** The OS's real path of an existing file; missing → NoSuchFileException, else IOException(reason). */
    public static native String realPath(String path) throws IOException;

    /** An absolute path as toRealPath reports one it cannot resolve (files.md). */
    public static native String lexical(String path);

    public static native boolean setReadOnly(String path);

    /** Grants or takes away read (4), write (2) or execute (1) access, for the owner or all. */
    public static native boolean setAccess(String path, int access, boolean enable, boolean ownerOnly);
}
