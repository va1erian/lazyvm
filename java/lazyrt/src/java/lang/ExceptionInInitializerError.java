package java.lang;

public class ExceptionInInitializerError extends LinkageError {
    public ExceptionInInitializerError() {
        initCause(null);
    }

    public ExceptionInInitializerError(Throwable thrown) {
        super(null, thrown);
    }

    public ExceptionInInitializerError(String s) {
        super(s);
        initCause(null);
    }

    public Throwable getException() {
        return getCause();
    }
}
