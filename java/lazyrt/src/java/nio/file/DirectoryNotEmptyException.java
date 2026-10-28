package java.nio.file;

public class DirectoryNotEmptyException extends FileSystemException {
    public DirectoryNotEmptyException(String file) {
        super(file);
    }
}
