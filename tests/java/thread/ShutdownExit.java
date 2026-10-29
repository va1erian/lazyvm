package thread;

/** System.exit from another thread runs the hooks and ends the VM with its status. */
public class ShutdownExit {
    public static void main(String[] args) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("hook ran")));
        Thread stuck = new Thread(() -> {
            Object o = new Object();
            synchronized (o) {
                try {
                    o.wait();
                } catch (InterruptedException e) {
                    System.out.println("unexpected interrupt");
                }
            }
        }, "stuck");
        stuck.start();
        Thread exiter = new Thread(() -> {
            System.out.println("exiting from " + Thread.currentThread().getName());
            System.exit(3);
            System.out.println("not reached");
        }, "exiter");
        exiter.start();
        exiter.join();
        System.out.println("not reached either");
    }
}
