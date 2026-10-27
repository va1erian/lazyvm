package java.nio.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import lazy.internal.FileSystem;

/** Files' operations that change the file system (nio-file.md NF4, NF5, NF9). */
final class FileOps {
    private FileOps() {
    }

    static long transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    private static boolean has(CopyOption[] options, CopyOption o) {
        return Arrays.asList(options).contains(o);
    }

    /** Observed: a null option is NPE, one outside `allowed` UnsupportedOperationException. */
    private static void check(CopyOption[] options, String message, CopyOption... allowed) {
        for (CopyOption o : options) {
            if (o == null) {
                throw new NullPointerException();
            }
            if (!Arrays.asList(allowed).contains(o)) {
                throw new UnsupportedOperationException(message == null ? o + " not supported" : message);
            }
        }
    }

    // Spec NF4: an existing target is FileAlreadyExistsException unless REPLACE_EXISTING.
    private static void clearTarget(Path target, CopyOption[] options) throws IOException {
        if (FileAccess.exists(target)) {
            if (!has(options, StandardCopyOption.REPLACE_EXISTING)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            delete(target);
        }
    }

    static long copy(InputStream in, Path target, CopyOption... options) throws IOException {
        check(options, null, StandardCopyOption.REPLACE_EXISTING);
        clearTarget(target, options);
        try (OutputStream out = FileAccess.openOutput(target, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            return transfer(in, out);
        }
    }

    // Spec NF4: a file onto itself is left alone; COPY_ATTRIBUTES keeps the time.
    static Path copy(Path source, Path target, CopyOption... options) throws IOException {
        check(options, "Unsupported copy option", StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
        FileAccess.requireExists(source);
        if (FileAccess.exists(target) && isSameFile(source, target)) {
            return target;
        }
        clearTarget(target, options);
        if (FileAccess.isDirectory(source)) {
            createDirectory(target);
        } else {
            try (InputStream in = FileAccess.openInput(source)) {
                OutputStream out;
                try {
                    out = FileAccess.openOutput(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                } catch (NoSuchFileException e) {
                    throw FileAccess.failure(source, target);
                }
                try (OutputStream o = out) {
                    transfer(in, o);
                }
            }
        }
        // Observed: a directory's times are not copied.
        if (has(options, StandardCopyOption.COPY_ATTRIBUTES) && !FileAccess.isDirectory(source)) {
            for (int which = FileSystem.MODIFIED; which <= FileSystem.CREATED; which++) {
                long us = FileSystem.fileTime(source.toString(), which);
                if (us != Long.MIN_VALUE) {
                    try {
                        FileSystem.setFileTime(target.toString(), which, us);
                    } catch (IOException e) {
                        // A time the target's file system does not keep is not copied.
                    }
                }
            }
        }
        return target;
    }

    // Spec NF4: a rename, or a copy and delete when the rename cannot be done (not with ATOMIC_MOVE).
    // Observed: a failed rename on one volume (a directory into itself, a file in use) is a
    // FileSystemException with the OS's reason, and changes nothing.
    static Path move(Path source, Path target, CopyOption... options) throws IOException {
        check(options, "Unsupported copy option", StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE, LinkOption.NOFOLLOW_LINKS);
        FileAccess.requireExists(source);
        if (FileAccess.exists(target) && isSameFile(source, target)) {
            return target;
        }
        boolean atomic = has(options, StandardCopyOption.ATOMIC_MOVE);
        // Observed: whatever is in the way is deleted before the rename, so a rename that then
        // fails (an open source) leaves the target gone.
        if (!atomic && FileAccess.exists(target)) {
            if (!has(options, StandardCopyOption.REPLACE_EXISTING)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            delete(target);
        }
        boolean moved;
        try {
            moved = FileSystem.move(source.toString(), target.toString());
        } catch (IOException e) {
            if (!FileAccess.exists(source)) {
                throw new NoSuchFileException(source.toString());
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null && !FileAccess.isDirectory(parent)) {
                throw new NoSuchFileException(source.toString(), target.toString(), null);
            }
            throw new FileSystemException(source.toString(), target.toString(), e.getMessage());
        }
        if (moved) {
            return target;
        }
        if (atomic) {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "Rename failed");
        }
        copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        delete(source);
        return target;
    }

    // Spec NF5: missing is NoSuchFileException, non-empty DirectoryNotEmptyException.
    static void delete(Path p) throws IOException {
        int attrs = FileAccess.attributes(p);
        if (attrs == 0) {
            throw new NoSuchFileException(p.toString());
        }
        // Observed on Windows: Files (unlike File) does not delete a read-only file.
        boolean windows = "\\".equals(p.getFileSystem().getSeparator());
        if (windows && (attrs & (FileSystem.WRITABLE | FileSystem.DIRECTORY)) == 0) {
            throw new AccessDeniedException(p.toString());
        }
        try {
            FileSystem.delete(p.toString());
        } catch (FileSystemException e) {
            throw e;
        } catch (IOException e) {
            // Observed: a file another handle holds open fails with the OS's text.
            throw new FileSystemException(p.toString(), null, e.getMessage());
        }
    }

    // Spec NF5: an existing name is FileAlreadyExistsException, a missing parent NoSuchFileException.
    static Path createDirectory(Path dir) throws IOException {
        if (FileAccess.exists(dir)) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        if (!FileSystem.mkdir(dir.toString())) {
            Path parent = dir.toAbsolutePath().getParent();
            if (parent != null && !FileAccess.isDirectory(parent)) {
                throw new NoSuchFileException(dir.toString());
            }
            throw new AccessDeniedException(dir.toString());
        }
        return dir;
    }

    // Spec NF5: an existing directory is fine, an existing file is FileAlreadyExistsException.
    // Observed: the missing names are made from the nearest existing ancestor down, so a file
    // in the way is NoSuchFileException for the name below it.
    static Path createDirectories(Path dir) throws IOException {
        if (FileAccess.isDirectory(dir)) {
            return dir;
        }
        if (FileAccess.exists(dir)) {
            throw new FileAlreadyExistsException(dir.toString());
        }
        java.util.Deque<Path> missing = new java.util.ArrayDeque<>();
        missing.push(dir);
        for (Path p = dir.toAbsolutePath().getParent(); p != null && !FileAccess.exists(p); p = p.getParent()) {
            missing.push(p);
        }
        while (!missing.isEmpty()) {
            Path p = missing.pop();
            try {
                createDirectory(p);
            } catch (FileAlreadyExistsException e) {
                if (!FileAccess.isDirectory(p)) {
                    throw e;
                }
            }
        }
        return dir;
    }

    // Observed on Windows: a directory in the way is AccessDeniedException.
    static Path createFile(Path p) throws IOException {
        if (!FileAccess.createNew(p)) {
            if ("\\".equals(p.getFileSystem().getSeparator()) && FileAccess.isDirectory(p)) {
                throw new AccessDeniedException(p.toString());
            }
            throw new FileAlreadyExistsException(p.toString());
        }
        return p;
    }

    // Spec NF5: prefix ("" if null) + digits + suffix (".tmp" if null; none for a directory).
    static Path createTemp(Path dir, String prefix, String suffix, boolean directory) throws IOException {
        Path base = dir != null ? dir : Paths.get(System.getProperty("java.io.tmpdir"));
        String pre = prefix == null ? "" : prefix;
        String suf = directory ? "" : suffix == null ? ".tmp" : suffix;
        while (true) {
            // Observed: a name that is not one plain name is refused, so nothing lands outside dir.
            Path name = base.getFileSystem().getPath(pre + lazy.internal.TempNames.next() + suf);
            if (name.getParent() != null || name.getNameCount() != 1) {
                throw new IllegalArgumentException("Invalid prefix or suffix");
            }
            Path p = base.resolve(name);
            if (directory) {
                try {
                    return createDirectory(p);
                } catch (FileAlreadyExistsException e) {
                    continue;
                }
            }
            if (FileAccess.createNew(p)) {
                return p;
            }
        }
    }

    // Spec NF5: equal paths are the same file without I/O; else both must exist.
    static boolean isSameFile(Path a, Path b) throws IOException {
        if (a.equals(b)) {
            return true;
        }
        FileAccess.requireExists(a);
        FileAccess.requireExists(b);
        return a.toRealPath().equals(b.toRealPath());
    }

    /** Spec NF3: lines as readLine splits them (no empty last line after a final terminator). */
    static List<String> readAllLines(java.io.BufferedReader r) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }
}
