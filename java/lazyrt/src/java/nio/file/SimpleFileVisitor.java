package java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;

/** Continues everywhere and rethrows failures (Spec NF8). */
public class SimpleFileVisitor<T> implements FileVisitor<T> {
    protected SimpleFileVisitor() {
    }

    public FileVisitResult preVisitDirectory(T dir, BasicFileAttributes attrs) throws IOException {
        if (dir == null || attrs == null) {
            throw new NullPointerException();
        }
        return FileVisitResult.CONTINUE;
    }

    public FileVisitResult visitFile(T file, BasicFileAttributes attrs) throws IOException {
        if (file == null || attrs == null) {
            throw new NullPointerException();
        }
        return FileVisitResult.CONTINUE;
    }

    public FileVisitResult visitFileFailed(T file, IOException exc) throws IOException {
        if (file == null) {
            throw new NullPointerException();
        }
        throw exc;
    }

    public FileVisitResult postVisitDirectory(T dir, IOException exc) throws IOException {
        if (dir == null) {
            throw new NullPointerException();
        }
        if (exc != null) {
            throw exc;
        }
        return FileVisitResult.CONTINUE;
    }
}
