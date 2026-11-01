package thread;

/** The main group still refuses destroy after main has returned. */
public class GroupAfterMain {
    public static void main(String[] args) {
        final Thread main = Thread.currentThread();
        final ThreadGroup group = main.getThreadGroup();
        Thread t = new Thread(new ThreadGroup("sub"), () -> {
            try {
                main.join();
            } catch (InterruptedException e) {
                System.out.println(e);
            }
            System.out.println("main ended, group destroyed " + group.isDestroyed());
            try {
                group.destroy();
                System.out.println("destroyed");
            } catch (IllegalThreadStateException e) {
                System.out.println("refused " + e);
            }
            System.out.println("group destroyed " + group.isDestroyed());
            System.out.println("system destroyed " + group.getParent().isDestroyed());
        }, "watcher");
        t.start();
    }
}
