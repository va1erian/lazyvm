package java.lang.reflect;

public class MalformedParametersException extends RuntimeException {
    public MalformedParametersException() {
    }

    public MalformedParametersException(String reason) {
        super(reason);
    }
}
