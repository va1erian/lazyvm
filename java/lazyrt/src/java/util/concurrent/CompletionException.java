package java.util.concurrent;

public class CompletionException extends RuntimeException {
    protected CompletionException() {
    }

    protected CompletionException(String message) {
        super(message);
    }

    public CompletionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CompletionException(Throwable cause) {
        super(cause);
    }
}
