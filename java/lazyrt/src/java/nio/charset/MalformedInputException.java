package java.nio.charset;

public class MalformedInputException extends CharacterCodingException {
    private final int inputLength;

    public MalformedInputException(int inputLength) {
        this.inputLength = inputLength;
    }

    public int getInputLength() {
        return inputLength;
    }

    // Spec K4: "Input length = n".
    public String getMessage() {
        return "Input length = " + inputLength;
    }
}
