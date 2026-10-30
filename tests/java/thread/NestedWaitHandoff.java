package thread;

/**
 * A wait() at nesting depth 3 gets its monitor back with all three entries,
 * also when the thread re-enters after a long wait for a busy monitor (the
 * VM then hands the monitor over instead of letting threads compete).
 */
public class NestedWaitHandoff {
    static final Object lock = new Object();
    static volatile boolean waiting;

    static String depths(boolean notified) {
        StringBuilder sb = new StringBuilder();
        try {
            synchronized (lock) {
                synchronized (lock) {
                    synchronized (lock) {
                        waiting = true;
                        // Notified, or timed out while main holds the lock.
                        lock.wait(notified ? 0 : 10);
                        sb.append(Thread.holdsLock(lock)).append(' ');
                    }
                    sb.append(Thread.holdsLock(lock)).append(' ');
                }
                sb.append(Thread.holdsLock(lock)).append(' ');
            }
            sb.append(Thread.holdsLock(lock));
        } catch (Throwable e) {
            sb.append(e);
        }
        return sb.toString();
    }

    static void round(final boolean notified) throws InterruptedException {
        final String[] out = new String[1];
        waiting = false;
        Thread t = new Thread(() -> out[0] = depths(notified));
        t.start();
        while (!waiting || t.getState() == Thread.State.RUNNABLE) {
            Thread.sleep(1);
        }
        synchronized (lock) {
            if (notified) {
                lock.notify();
            }
            // Hold the monitor while the waiter queues to get it back.
            Thread.sleep(50);
        }
        t.join();
        System.out.println((notified ? "notified" : "timed out") + ", holdsLock at depths 3 2 1 0: " + out[0]);
    }

    public static void main(String[] args) throws InterruptedException {
        round(true);
        round(false);
    }
}
