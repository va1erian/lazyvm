package java.nio.file;

import java.io.IOException;
import java.util.ConcurrentModificationException;

public final class DirectoryIteratorException extends ConcurrentModificationException {
    public DirectoryIteratorException(IOException cause) {
        super(cause);
        if (cause == null) {
            throw new NullPointerException();
        }
    }

    public IOException getCause() {
        return (IOException) super.getCause();
    }
}
