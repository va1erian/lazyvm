package java.nio.charset;

public class UnmappableCharacterException extends CharacterCodingException {
    private final int inputLength;

    public UnmappableCharacterException(int inputLength) {
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
