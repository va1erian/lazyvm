package java.util;

import java.io.IOException;

/** Thrown when an XML properties document is not valid; LazyVM has no XML reader yet. */
public class InvalidPropertiesFormatException extends IOException {
    private static final long serialVersionUID = 7763056076009360219L;

    public InvalidPropertiesFormatException(Throwable cause) {
        super(cause);
    }

    public InvalidPropertiesFormatException(String message) {
        super(message);
    }
}
