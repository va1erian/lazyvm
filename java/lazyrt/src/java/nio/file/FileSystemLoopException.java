package java.nio.file;

public class FileSystemLoopException extends FileSystemException {
    public FileSystemLoopException(String file) {
        super(file);
    }
}
