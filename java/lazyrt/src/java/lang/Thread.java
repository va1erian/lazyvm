package java.lang;

/** A placeholder Thread until S6: one "main" thread, and start() is unsupported. */
public class Thread implements Runnable {
    public static final int MIN_PRIORITY = 1;
    public static final int NORM_PRIORITY = 5;
    public static final int MAX_PRIORITY = 10;

    private static Thread mainThread;
    private static int nextNumber;
    private static long nextId = 1;

    private volatile String name;
    private int priority = NORM_PRIORITY;
    private boolean daemon;
    private Runnable target;
    private final long id;

    public Thread() {
        this(null, "Thread-" + nextNumber++);
    }

    public Thread(Runnable target) {
        this(target, "Thread-" + nextNumber++);
    }

    public Thread(String name) {
        this(null, name);
    }

    public Thread(Runnable target, String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        this.name = name;
        this.target = target;
        this.id = nextId++;
    }

    public static synchronized Thread currentThread() {
        if (mainThread == null) {
            // The main thread does not use up a "Thread-N" number.
            mainThread = new Thread((Runnable) null, "main");
        }
        return mainThread;
    }

    public static native void sleep(long millis) throws InterruptedException;

    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        if (nanos >= 500000 || (nanos != 0 && millis == 0)) {
            millis++;
        }
        sleep(millis);
    }

    public static void yield() {
    }

    public static boolean interrupted() {
        return false;
    }

    public boolean isInterrupted() {
        return false;
    }

    public void interrupt() {
    }

    public final boolean isAlive() {
        return false;
    }

    public void start() {
        throw new UnsupportedOperationException("threads arrive in stage S6");
    }

    public void run() {
        if (target != null) {
            target.run();
        }
    }

    public final void join() throws InterruptedException {
    }

    public final void join(long millis) throws InterruptedException {
    }

    public final void join(long millis, int nanos) throws InterruptedException {
    }

    public final String getName() {
        return name;
    }

    public final void setName(String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        this.name = name;
    }

    public final int getPriority() {
        return priority;
    }

    public final void setPriority(int newPriority) {
        if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
            throw new IllegalArgumentException();
        }
        priority = newPriority;
    }

    public final boolean isDaemon() {
        return daemon;
    }

    public final void setDaemon(boolean on) {
        daemon = on;
    }

    public long getId() {
        return id;
    }

    public String toString() {
        return "Thread[" + name + "," + priority + ",main]";
    }
}
