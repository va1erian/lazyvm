package java.lang;

public class BootstrapMethodError extends LinkageError {
    public BootstrapMethodError() {
    }

    public BootstrapMethodError(String message) {
        super(message);
    }

    public BootstrapMethodError(String message, Throwable cause) {
        super(message, cause);
    }

    public BootstrapMethodError(Throwable cause) {
        super(cause == null ? null : cause.toString(), cause);
    }
}
