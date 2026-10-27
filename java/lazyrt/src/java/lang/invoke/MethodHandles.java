package java.lang.invoke;

/** Present so lambdas compile; LazyVM supports no method handle API yet. */
public class MethodHandles {
    private MethodHandles() {
    }

    /** A lookup context; only named by lambda bootstrap descriptors. */
    public static final class Lookup {
        private Lookup() {
        }
    }
}
