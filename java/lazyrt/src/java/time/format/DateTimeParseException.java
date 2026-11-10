package java.time.format;

import java.time.DateTimeException;

public class DateTimeParseException extends DateTimeException {
    private final String parsed;
    private final int errorIndex;

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex) {
        super(message);
        this.parsed = parsedData.toString();
        this.errorIndex = errorIndex;
    }

    public DateTimeParseException(String message, CharSequence parsedData, int errorIndex, Throwable cause) {
        super(message, cause);
        this.parsed = parsedData.toString();
        this.errorIndex = errorIndex;
    }

    public String getParsedString() {
        return parsed;
    }

    public int getErrorIndex() {
        return errorIndex;
    }
}
