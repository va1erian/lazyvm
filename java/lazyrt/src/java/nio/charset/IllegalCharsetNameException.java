package java.nio.charset;

/** Spec N2: the message is the charset name. */
public class IllegalCharsetNameException extends IllegalArgumentException {
    private final String charsetName;

    public IllegalCharsetNameException(String charsetName) {
        super(String.valueOf(charsetName));
        this.charsetName = charsetName;
    }

    public String getCharsetName() {
        return charsetName;
    }
}
