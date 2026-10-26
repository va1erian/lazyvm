package java.lang.invoke;

/** Thrown when a lambda cannot be linked. */
public class LambdaConversionException extends Exception {
    public LambdaConversionException() {
    }

    public LambdaConversionException(String message) {
        super(message);
    }

    public LambdaConversionException(String message, Throwable cause) {
        super(message, cause);
    }

    public LambdaConversionException(Throwable cause) {
        super(cause);
    }

    public LambdaConversionException(String message, Throwable cause, boolean enableSuppression,
            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
