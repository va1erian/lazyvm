package java.nio.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lazy.internal.FileSystem;

/**
 * How Files opens files: the open options (nio-file.md NF2, NF3), the
 * FileSystemExceptions an OS failure becomes (NF1), and the streams over
 * a lazy.internal.FileSystem handle.
 */
final class FileAccess {
    private FileAccess() {
    }

    static int attributes(Path p) {
        return FileSystem.attributes(p.toString());
    }

    static boolean exists(Path p) {
        return (attributes(p) & FileSystem.EXISTS) != 0;
    }

    static boolean isDirectory(Path p) {
        return (attributes(p) & FileSystem.DIRECTORY) != 0;
    }

    /** Spec NF1: what a failed operation on `p` (and `other`) reports, with the path as given. */
    static IOException failure(Path p, Path other) {
        String file = p.toString();
        String o = other == null ? null : other.toString();
        if (!exists(p)) {
            return new NoSuchFileException(file, o, null);
        }
        if (other != null) {
            Path parent = other.toAbsolutePath().getParent();
            if (parent != null && !exists(parent)) {
                return new NoSuchFileException(file, o, null);
            }
        }
        return new AccessDeniedException(file, o, null);
    }

    static void requireExists(Path p) throws NoSuchFileException {
        if (!exists(p)) {
            throw new NoSuchFileException(p.toString());
        }
    }

    // Spec NF3: reading allows READ (and the link and no-op options) only.
    static InputStream openInput(Path p, OpenOption... options) throws IOException {
        checkOptions(options);
        for (OpenOption o : options) {
            if (o == StandardOpenOption.APPEND || o == StandardOpenOption.WRITE) {
                throw new UnsupportedOperationException("'" + o + "' not allowed");
            }
        }
        long h;
        try {
            h = FileSystem.open(p.toString(), FileSystem.READ | FileSystem.SHARE_DELETE);
        } catch (IOException e) {
            // Observed: a directory is AccessDeniedException, as for any existing file.
            throw failure(p, null);
        }
        return new HandleInput(h);
    }

    // Spec NF2: CREATE, TRUNCATE_EXISTING and WRITE by default; the checks of the options.
    static OutputStream openOutput(Path p, OpenOption... options) throws IOException {
        checkOptions(options);
        Set<OpenOption> opts = new HashSet<>(Arrays.asList(options));
        if (opts.isEmpty()) {
            opts.add(StandardOpenOption.CREATE);
            opts.add(StandardOpenOption.TRUNCATE_EXISTING);
            opts.add(StandardOpenOption.WRITE);
        }
        if (opts.contains(StandardOpenOption.READ)) {
            throw new IllegalArgumentException("READ not allowed");
        }
        boolean append = opts.contains(StandardOpenOption.APPEND);
        if (append && opts.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            throw new IllegalArgumentException("APPEND + TRUNCATE_EXISTING not allowed");
        }
        String name = p.toString();
        if (opts.contains(StandardOpenOption.CREATE_NEW)) {
            if (!createNew(p)) {
                throw new FileAlreadyExistsException(name);
            }
        } else if (!exists(p) && !opts.contains(StandardOpenOption.CREATE)) {
            throw new NoSuchFileException(name);
        }
        int mode = append ? FileSystem.APPEND
                : opts.contains(StandardOpenOption.TRUNCATE_EXISTING) ? FileSystem.WRITE : FileSystem.READ_WRITE;
        long h;
        try {
            h = FileSystem.open(name, mode | FileSystem.SHARE_DELETE);
        } catch (IOException e) {
            throw failure(p, null);
        }
        boolean sync = opts.contains(StandardOpenOption.SYNC) || opts.contains(StandardOpenOption.DSYNC);
        return new HandleOutput(h, sync, opts.contains(StandardOpenOption.DELETE_ON_CLOSE) ? name : null);
    }

    /** Javadoc: a null option is NPE, one this file system does not know UnsupportedOperationException. */
    private static void checkOptions(OpenOption[] options) {
        for (OpenOption o : options) {
            if (o == null) {
                throw new NullPointerException();
            }
            if (!(o instanceof StandardOpenOption) && !(o instanceof LinkOption)) {
                throw new UnsupportedOperationException();
            }
        }
    }

    /** Atomically creates an empty file: false if something is there; a missing directory is NSFE. */
    static boolean createNew(Path p) throws IOException {
        try {
            return FileSystem.createNew(p.toString());
        } catch (IOException e) {
            throw failure(p, null);
        }
    }

    /** Spec NF3: available() is what is left; skip stops at the end; no mark. */
    private static final class HandleInput extends InputStream {
        private final long h;
        private final byte[] one = new byte[1];

        HandleInput(long h) {
            this.h = h;
        }

        public int read() throws IOException {
            return FileSystem.read(h, one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        public int read(byte[] b, int off, int len) throws IOException {
            return FileSystem.read(h, b, off, len);
        }

        public int available() throws IOException {
            return FileSystem.available(h);
        }

        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            long pos = FileSystem.position(h);
            long k = Math.min(n, Math.max(0, FileSystem.size(h) - pos));
            FileSystem.seek(h, pos + k);
            return k;
        }

        public void close() {
            FileSystem.close(h);
        }
    }

    private static final class HandleOutput extends OutputStream {
        private final long h;
        private final boolean sync;
        private final String deleteOnClose;
        private final byte[] one = new byte[1];
        private boolean closed;

        HandleOutput(long h, boolean sync, String deleteOnClose) {
            this.h = h;
            this.sync = sync;
            this.deleteOnClose = deleteOnClose;
        }

        public void write(int b) throws IOException {
            one[0] = (byte) b;
            write(one, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            FileSystem.write(h, b, off, len);
            if (sync) {
                FileSystem.sync(h);
            }
        }

        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            FileSystem.close(h);
            if (deleteOnClose != null) {
                try {
                    FileSystem.delete(deleteOnClose);
                } catch (IOException e) {
                    // Spec: DELETE_ON_CLOSE is best effort.
                }
            }
        }
    }
}
