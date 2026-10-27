package java.lang;

public class NumberFormatException extends IllegalArgumentException {
    public NumberFormatException() {
    }

    public NumberFormatException(String s) {
        super(s);
    }

    /** The exception the parse methods throw for a malformed input. */
    static NumberFormatException forInputString(String s) {
        return new NumberFormatException("For input string: \"" + s + "\"");
    }
}
