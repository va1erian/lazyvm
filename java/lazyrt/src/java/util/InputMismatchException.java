package java.util;

/** Thrown by Scanner when a token does not have the expected form (util-text.md section 5). */
public class InputMismatchException extends NoSuchElementException {
    public InputMismatchException() {
    }

    public InputMismatchException(String s) {
        super(s);
    }
}
