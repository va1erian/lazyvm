package thread;

/** System.exit still ends the VM with its status when a hook cannot be started. */
public class ShutdownDeadHook {
    public static void main(String[] args) throws Exception {
        Thread done = new Thread(() -> System.out.println("ran once"));
        done.start();
        done.join();
        Runtime.getRuntime().addShutdownHook(done);
        System.exit(6);
    }
}
