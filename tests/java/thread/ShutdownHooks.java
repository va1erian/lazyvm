package thread;

/** Runtime.addShutdownHook/removeShutdownHook; hooks run after the last non-daemon thread. */
public class ShutdownHooks {
    public static void main(String[] args) throws Exception {
        Runtime rt = Runtime.getRuntime();
        final Thread first = new Thread(() -> System.out.println("hook one, in " + Thread.currentThread().getName()), "one");
        Thread second = new Thread(() -> {
            try {
                first.join();
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
            System.out.println("hook two, after one");
            try {
                Runtime.getRuntime().addShutdownHook(new Thread());
            } catch (IllegalStateException e) {
                System.out.println("add during shutdown: " + e);
            }
            try {
                Runtime.getRuntime().removeShutdownHook(first);
            } catch (IllegalStateException e) {
                System.out.println("remove during shutdown: " + e);
            }
        }, "two");
        Thread removed = new Thread(() -> System.out.println("removed hook ran"));
        rt.addShutdownHook(first);
        rt.addShutdownHook(second);
        rt.addShutdownHook(removed);
        try {
            rt.addShutdownHook(first);
        } catch (IllegalArgumentException e) {
            System.out.println("duplicate: " + e);
        }
        Thread running = new Thread(() -> { });
        running.start();
        running.join();
        try {
            rt.addShutdownHook(running);
            System.out.println("a finished thread is accepted");
            rt.removeShutdownHook(running);
        } catch (IllegalArgumentException e) {
            System.out.println("finished thread: " + e);
        }
        final Object lock = new Object();
        Thread alive = new Thread(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    System.out.println("unexpected interrupt");
                }
            }
        });
        alive.setDaemon(true);
        alive.start();
        try {
            rt.addShutdownHook(alive);
        } catch (IllegalArgumentException e) {
            System.out.println("live thread: " + e);
        }
        try {
            rt.addShutdownHook(null);
        } catch (NullPointerException e) {
            System.out.println("null hook: " + e);
        }
        try {
            rt.removeShutdownHook(null);
        } catch (NullPointerException e) {
            System.out.println("remove null: " + e);
        }
        System.out.println("remove registered: " + rt.removeShutdownHook(removed));
        System.out.println("remove again: " + rt.removeShutdownHook(removed));
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
            System.out.println("worker ends after main");
        });
        worker.start();
        System.out.println("main returns");
    }
}
