package java.util;

public class IllegalFormatFlagsException extends IllegalFormatException {
    private final String f;

    public IllegalFormatFlagsException(String f) {
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
