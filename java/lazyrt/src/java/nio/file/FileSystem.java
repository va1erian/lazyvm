package java.nio.file;

import java.io.Closeable;
import java.io.IOException;

/** The file system API LazyVM provides; providers and file stores are out of scope (io.md 15). */
public abstract class FileSystem implements Closeable {
    protected FileSystem() {
    }

    public abstract void close() throws IOException;

    public abstract boolean isOpen();

    public abstract boolean isReadOnly();

    public abstract String getSeparator();

    public abstract Iterable<Path> getRootDirectories();

    public abstract Path getPath(String first, String... more);

    public abstract PathMatcher getPathMatcher(String syntaxAndPattern);
}
