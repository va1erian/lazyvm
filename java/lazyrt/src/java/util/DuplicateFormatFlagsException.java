package java.util;

public class DuplicateFormatFlagsException extends IllegalFormatException {
    private final String f;

    public DuplicateFormatFlagsException(String f) {
        if (f == null) {
            throw new NullPointerException();
        }
        this.f = f;
    }

    public String getFlags() {
        return f;
    }

    public String getMessage() {
        return "Flags = '" + f + "'";
    }
}
