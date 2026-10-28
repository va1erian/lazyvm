package java.nio.file;

public class InvalidPathException extends IllegalArgumentException {
    private final String input;
    private final int index;

    public InvalidPathException(String input, String reason, int index) {
        super(reason);
        if (input == null || reason == null) {
            throw new NullPointerException();
        }
        if (index < -1) {
            throw new IllegalArgumentException();
        }
        this.input = input;
        this.index = index;
    }

    public InvalidPathException(String input, String reason) {
        this(input, reason, -1);
    }

    public String getInput() {
        return input;
    }

    public String getReason() {
        return super.getMessage();
    }

    public int getIndex() {
        return index;
    }

    // Spec PW1: "reason at index i: input", without the index when it is -1.
    public String getMessage() {
        return getReason() + (index > -1 ? " at index " + index : "") + ": " + input;
    }
}
