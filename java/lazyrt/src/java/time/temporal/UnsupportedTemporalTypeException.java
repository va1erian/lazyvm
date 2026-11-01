package java.time.temporal;

import java.time.DateTimeException;

public class UnsupportedTemporalTypeException extends DateTimeException {
    public UnsupportedTemporalTypeException(String message) {
        super(message);
    }

    public UnsupportedTemporalTypeException(String message, Throwable cause) {
        super(message, cause);
    }
}
