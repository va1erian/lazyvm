package java.nio.file;

public class FileSystemNotFoundException extends RuntimeException {
    public FileSystemNotFoundException() {
    }

    public FileSystemNotFoundException(String msg) {
        super(msg);
    }
}
