package thread;

/** System.exit from a hook blocks; the other hooks go on, and one halts the VM. */
public class ShutdownExitInHook {
    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        rt.addShutdownHook(new Thread(() -> {
            System.out.println("hook one calls exit");
            System.exit(5);
            System.out.println("not reached");
        }));
        rt.addShutdownHook(new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
            System.out.println("hook two still ran");
            Runtime.getRuntime().halt(7);
        }));
        System.out.println("main returns");
    }
}
