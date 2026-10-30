package thread;

/** Runtime.halt ends the VM without running the hooks. */
public class ShutdownHalt {
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        System.out.println("halting");
        Runtime.getRuntime().halt(5);
    }
}
