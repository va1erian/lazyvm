package java.util;

public class UnknownFormatFlagsException extends IllegalFormatException {
    private final String f;

    public UnknownFormatFlagsException(String f) {
        if (f == null) {
            throw new NullPointerException();
        }
        this.f = f;
    }

    public String getFlags() {
        return f;
    }

    public String getMessage() {
        return "Flags = " + f;
    }
}
