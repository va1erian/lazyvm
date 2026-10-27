package java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.NoSuchElementException;
import lazy.internal.FileSystem;

/** Directory listing and walking (io.md NF7, NF8). */
final class FileTree {
    private FileTree() {
    }

    // Spec NF7: missing is NoSuchFileException, a file NotDirectoryException.
    static DirectoryStream<Path> open(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        String name = dir.toString();
        int bits = FileSystem.attributes(name);
        if (bits == 0) {
            throw new NoSuchFileException(name);
        }
        if ((bits & FileSystem.DIRECTORY) == 0) {
            throw new NotDirectoryException(name);
        }
        String[] names = FileSystem.list(name);
        if (names == null) {
            throw new AccessDeniedException(name);
        }
        return new Listing(dir, names, filter);
    }

    /** Spec NF7: a glob that sees the entry's file name only. */
    static DirectoryStream.Filter<Path> glob(String pattern) {
        final PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        return new DirectoryStream.Filter<Path>() {
            public boolean accept(Path entry) {
                return m.matches(entry.getFileName());
            }
        };
    }

    /** The entries of one directory, sorted by name (files.md), as dir.resolve(name). */
    private static final class Listing implements DirectoryStream<Path> {
        private final Path dir;
        private final String[] names;
        private final DirectoryStream.Filter<? super Path> filter;
        private boolean closed;
        private boolean iterated;

        Listing(Path dir, String[] names, DirectoryStream.Filter<? super Path> filter) {
            this.dir = dir;
            this.names = names;
            this.filter = filter;
        }

        public void close() {
            closed = true;
        }

        // Spec NF7: one iterator only, none after close; a closed stream ends early.
        public Iterator<Path> iterator() {
            if (closed) {
                throw new IllegalStateException("Directory stream is closed");
            }
            if (iterated) {
                throw new IllegalStateException("Iterator already obtained");
            }
            iterated = true;
            return new Iterator<Path>() {
                private int i;
                private Path next;

                public boolean hasNext() {
                    while (next == null && !closed && i < names.length) {
                        Path p = dir.resolve(names[i++]);
                        try {
                            if (filter == null || filter.accept(p)) {
                                next = p;
                            }
                        } catch (IOException e) {
                            throw new DirectoryIteratorException(e);
                        }
                    }
                    return next != null;
                }

                public Path next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    Path p = next;
                    next = null;
                    return p;
                }

                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    // Spec NF8: depth-first in directory order; entries at maxDepth are visited as files.
    static Path walk(Path start, int maxDepth, FileVisitor<? super Path> visitor) throws IOException {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("'maxDepth' is negative");
        }
        visit(start, 0, maxDepth, visitor);
        return start;
    }

    /** One entry and, for a directory within the depth, its subtree; returns how to go on. */
    private static FileVisitResult visit(Path p, int depth, int maxDepth, FileVisitor<? super Path> v)
            throws IOException {
        BasicFileAttributes attrs;
        try {
            attrs = FileAttrs.read(p);
        } catch (IOException e) {
            return v.visitFileFailed(p, e);
        }
        if (!attrs.isDirectory() || depth >= maxDepth) {
            return v.visitFile(p, attrs);
        }
        DirectoryStream<Path> entries;
        try {
            entries = open(p, null);
        } catch (IOException e) {
            return v.visitFileFailed(p, e);
        }
        try (DirectoryStream<Path> ds = entries) {
            FileVisitResult r = v.preVisitDirectory(p, attrs);
            if (r != FileVisitResult.CONTINUE) {
                // Spec NF8: SKIP_SUBTREE skips the post-visit too.
                return r == FileVisitResult.SKIP_SUBTREE ? FileVisitResult.CONTINUE : r;
            }
            for (Path child : ds) {
                r = visit(child, depth + 1, maxDepth, v);
                if (r == FileVisitResult.TERMINATE) {
                    return r;
                }
                if (r == FileVisitResult.SKIP_SIBLINGS) {
                    break;
                }
            }
        }
        FileVisitResult post = v.postVisitDirectory(p, null);
        return post == FileVisitResult.SKIP_SIBLINGS ? FileVisitResult.CONTINUE : post;
    }
}
