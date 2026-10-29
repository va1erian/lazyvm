package thread;

/** An exception escaping main is reported, then the hooks run; the status is 1. */
public class ShutdownUncaught {
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        throw new IllegalStateException("main fails");
    }
}
