package java.lang;

/** A thread is not in the state an operation needs, such as starting it twice. */
public class IllegalThreadStateException extends IllegalArgumentException {
    public IllegalThreadStateException() {
    }

    public IllegalThreadStateException(String s) {
        super(s);
    }
}
