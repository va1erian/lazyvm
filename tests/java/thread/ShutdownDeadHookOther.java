package thread;

/** The same, with System.exit called from another thread while main joins it. */
public class ShutdownDeadHookOther {
    public static void main(String[] args) throws Exception {
        Thread done = new Thread(() -> System.out.println("ran once"));
        done.start();
        done.join();
        Runtime.getRuntime().addShutdownHook(done);
        Thread exiter = new Thread(() -> System.exit(6));
        exiter.start();
        exiter.join();
        System.out.println("not reached");
    }
}
