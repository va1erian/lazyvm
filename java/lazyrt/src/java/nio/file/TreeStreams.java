package java.nio.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** The stream methods of Files (nio-file.md section 15): lines, list, walk and find. */
final class TreeStreams {
    private TreeStreams() {
    }

    static Stream<String> lines(BufferedReader reader) {
        return lazy.internal.Lines.stream(reader).onClose(() -> {
            try {
                reader.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The entries of a directory, read in one go: used by walk, which holds no directory open. */
    private static List<Path> children(Path dir) throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                out.add(p);
            }
        }
        return out;
    }

    /** list: the directory is checked now and opened when the stream first reads, one entry at a time. */
    static Stream<Path> list(Path dir) throws IOException {
        Files.newDirectoryStream(dir).close();
        Entries entries = new Entries(dir);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(entries, Spliterator.DISTINCT), false)
                .onClose(entries::close);
    }

    /** The entries of one directory, read from the open directory as they are asked for. */
    private static final class Entries implements Iterator<Path> {
        private final Path dir;
        private DirectoryStream<Path> open;
        private Iterator<Path> source;
        private Path ahead;
        private boolean closed;

        Entries(Path dir) { this.dir = dir; }

        @Override
        public boolean hasNext() {
            if (ahead != null) {
                return true;
            }
            if (closed) {
                return false;
            }
            try {
                if (source == null) {
                    open = Files.newDirectoryStream(dir);
                    source = open.iterator();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (source.hasNext()) {
                ahead = source.next();
            }
            return ahead != null;
        }

        @Override
        public Path next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Path p = ahead;
            ahead = null;
            return p;
        }

        void close() {
            closed = true;
            try {
                if (open != null) {
                    open.close();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static Stream<Path> walk(Path start, int maxDepth, FileVisitOption[] options) throws IOException {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("'maxDepth' is negative");
        }
        for (FileVisitOption o : options) {
            Objects.requireNonNull(o);
        }
        Files.readAttributes(start, BasicFileAttributes.class);
        Walk walk = new Walk(start, maxDepth);
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(walk, 0), false).onClose(walk::close);
    }

    static Stream<Path> find(Path start, int maxDepth, BiPredicate<Path, BasicFileAttributes> matcher,
            FileVisitOption[] options) throws IOException {
        return walk(start, maxDepth, options).filter(p -> {
            try {
                return matcher.test(p, Files.readAttributes(p, BasicFileAttributes.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** Pre-order: a path comes before its entries; the entries of a directory are read when it is returned. */
    private static final class Walk implements Iterator<Path> {
        private final int maxDepth;
        private final ArrayDeque<Iterator<Path>> open = new ArrayDeque<>();
        private Path next;
        private boolean closed;

        void close() { closed = true; }

        Walk(Path start, int maxDepth) {
            this.maxDepth = maxDepth;
            this.next = start;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                throw new IllegalStateException();
            }
            return next != null;
        }

        @Override
        public Path next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Path p = next;
            descend(p);
            next = following();
            return p;
        }

        private void descend(Path p) {
            if (open.size() < maxDepth && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    open.push(children(p).iterator());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        private Path following() {
            while (!open.isEmpty()) {
                Iterator<Path> it = open.peek();
                if (it.hasNext()) {
                    return it.next();
                }
                open.pop();
            }
            return null;
        }
    }
}
