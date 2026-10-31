package java.lang;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A thread of execution, run by the VM on an OS thread of its own
 * (docs/design/threads.md). The VM calls {@code runByVm} on the new thread
 * and {@code exitByVm} for the main thread; both names are part of the
 * native contract (docs/design/natives.md).
 */
public class Thread implements Runnable {
    public static final int MIN_PRIORITY = 1;
    public static final int NORM_PRIORITY = 5;
    public static final int MAX_PRIORITY = 10;

    private static final int NEW = 0;
    private static final int STARTED = 1;
    private static final int TERMINATED = 2;

    /** The parent of every group; the main thread's group is its child. */
    static final ThreadGroup SYSTEM_GROUP = new ThreadGroup();
    private static final ThreadGroup MAIN_GROUP = new ThreadGroup(SYSTEM_GROUP, "main");

    private static int nextNumber;
    /** The main thread is 1; other threads count on from 2. */
    private static long nextId = 2;
    private static volatile UncaughtExceptionHandler defaultHandler;

    private volatile String name;
    private int priority;
    private boolean daemon;
    private Runnable target;
    private ThreadGroup group;
    private final long tid;
    private volatile int status;
    private volatile UncaughtExceptionHandler handler;
    private ClassLoader contextClassLoader;
    /** What LockSupport says this thread is parked on; only LockSupport touches it. */
    private Object parkedFor;
    /** Values of this thread's ThreadLocals and InheritableThreadLocals. */
    Map<ThreadLocal<?>, Object> locals;
    Map<ThreadLocal<?>, Object> inheritableLocals;

    public Thread() {
        this(null, null, nextName());
    }

    public Thread(Runnable target) {
        this(null, target, nextName());
    }

    public Thread(ThreadGroup group, Runnable target) {
        this(group, target, nextName());
    }

    public Thread(String name) {
        this(null, null, name);
    }

    public Thread(ThreadGroup group, String name) {
        this(group, null, name);
    }

    public Thread(Runnable target, String name) {
        this(null, target, name);
    }

    public Thread(ThreadGroup group, Runnable target, String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        Thread parent = currentThread();
        if (group == null) {
            group = parent.getThreadGroup();
        }
        this.group = group;
        this.name = name;
        this.target = target;
        this.daemon = parent.isDaemon();
        this.priority = Math.min(parent.getPriority(), group.getMaxPriority());
        this.contextClassLoader = parent.contextClassLoader;
        this.tid = nextId();
        inheritLocals(parent);
    }

    public Thread(ThreadGroup group, Runnable target, String name, long stackSize) {
        // The stack size is a hint the Javadoc lets a VM ignore.
        this(group, target, name);
    }

    /** The main thread, made the first time it asks who it is. */
    private Thread(ThreadGroup group) {
        this.group = group;
        this.name = "main";
        this.priority = NORM_PRIORITY;
        this.tid = 1;
        this.status = STARTED;
    }

    private static synchronized String nextName() {
        return "Thread-" + nextNumber++;
    }

    private static synchronized long nextId() {
        return nextId++;
    }

    private void inheritLocals(Thread parent) {
        Map<ThreadLocal<?>, Object> from = parent.inheritableLocals;
        if (from == null) {
            return;
        }
        inheritableLocals = new IdentityHashMap<ThreadLocal<?>, Object>();
        for (Map.Entry<ThreadLocal<?>, Object> e : from.entrySet()) {
            InheritableThreadLocal<?> key = (InheritableThreadLocal<?>) e.getKey();
            inheritableLocals.put(key, key.childValueOf(e.getValue()));
        }
    }

    public static Thread currentThread() {
        Thread t = current0();
        if (t == null) {
            // Only the main thread can be without one, and only once.
            t = new Thread(MAIN_GROUP);
            attach0(t);
        }
        return t;
    }

    private static native Thread current0();

    private static native void attach0(Thread t);

    public static native void sleep(long millis) throws InterruptedException;

    public static void sleep(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        // Rounding Long.MAX_VALUE up wraps it negative, which Java 8 reports
        // as a negative timeout (observed); so does join below.
        if (nanos >= 500000 || (nanos != 0 && millis == 0)) {
            millis++;
        }
        sleep(millis);
    }

    public static native void yield();

    public static native boolean holdsLock(Object obj);

    public static boolean interrupted() {
        return currentThread().isInterrupted0(true);
    }

    public boolean isInterrupted() {
        return isInterrupted0(false);
    }

    public void interrupt() {
        interrupt0();
    }

    private native boolean isInterrupted0(boolean clear);

    private native void interrupt0();

    public final boolean isAlive() {
        return status == STARTED;
    }

    public synchronized void start() {
        if (status != NEW) {
            throw new IllegalThreadStateException();
        }
        status = STARTED;
        boolean started = false;
        try {
            start0(daemon);
            started = true;
        } finally {
            if (!started) {
                status = NEW;
            }
        }
    }

    private native void start0(boolean daemon);

    public void run() {
        if (target != null) {
            target.run();
        }
    }

    /** The body of a started thread, called by the VM on that thread. */
    private void runByVm() {
        try {
            run();
        } catch (Throwable t) {
            dispatchUncaughtException(t);
        } finally {
            exitByVm();
        }
    }

    private void dispatchUncaughtException(Throwable t) {
        try {
            getUncaughtExceptionHandler().uncaughtException(this, t);
        } catch (Throwable u) {
            lazy.internal.VM.handlerFailed(u);
        }
    }

    /** Ends this thread for Java code: wakes its joiners, drops its state. */
    private void exitByVm() {
        synchronized (this) {
            status = TERMINATED;
            notifyAll();
        }
        group = null;
        target = null;
        handler = null;
        locals = null;
        inheritableLocals = null;
    }

    public final void join() throws InterruptedException {
        join(0);
    }

    public final synchronized void join(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (millis == 0) {
            while (isAlive()) {
                wait(0);
            }
            return;
        }
        // Elapsed time, not a deadline: millis * 10^6 could overflow.
        long start = System.nanoTime();
        long left = millis;
        while (isAlive() && left > 0) {
            wait(left);
            left = millis - (System.nanoTime() - start) / 1000000L;
        }
    }

    public final void join(long millis, int nanos) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        if (nanos < 0 || nanos > 999999) {
            throw new IllegalArgumentException("nanosecond timeout value out of range");
        }
        if (nanos >= 500000 || (nanos != 0 && millis == 0)) {
            millis++;
        }
        join(millis);
    }

    public final String getName() {
        return name;
    }

    public final synchronized void setName(String name) {
        if (name == null) {
            throw new NullPointerException("name cannot be null");
        }
        this.name = name;
    }

    public final int getPriority() {
        return priority;
    }

    /** Stored and reported; the OS scheduler is not told (docs/design/threads.md). */
    public final void setPriority(int newPriority) {
        if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
            throw new IllegalArgumentException();
        }
        ThreadGroup g = group;
        if (g != null) {
            priority = Math.min(newPriority, g.getMaxPriority());
        }
    }

    public final boolean isDaemon() {
        return daemon;
    }

    public final void setDaemon(boolean on) {
        if (isAlive()) {
            throw new IllegalThreadStateException();
        }
        daemon = on;
    }

    public final ThreadGroup getThreadGroup() {
        return group;
    }

    public final void checkAccess() {
    }

    public long getId() {
        return tid;
    }

    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }

    public void setContextClassLoader(ClassLoader cl) {
        contextClassLoader = cl;
    }

    public State getState() {
        int s = status;
        if (s == NEW) {
            return State.NEW;
        }
        if (s == TERMINATED) {
            return State.TERMINATED;
        }
        switch (state0()) {
            case 0:
                return State.RUNNABLE;
            case 1:
                return State.BLOCKED;
            case 2:
                return State.WAITING;
            case 3:
                return State.TIMED_WAITING;
            default:
                return State.TERMINATED;
        }
    }

    private native int state0();

    public static void dumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    public static void setDefaultUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        defaultHandler = eh;
    }

    public static UncaughtExceptionHandler getDefaultUncaughtExceptionHandler() {
        return defaultHandler;
    }

    public UncaughtExceptionHandler getUncaughtExceptionHandler() {
        UncaughtExceptionHandler h = handler;
        return h != null ? h : group;
    }

    public void setUncaughtExceptionHandler(UncaughtExceptionHandler eh) {
        handler = eh;
    }

    public String toString() {
        ThreadGroup g = group;
        return "Thread[" + name + "," + priority + "," + (g != null ? g.getName() : "") + "]";
    }

    public enum State {
        NEW,
        RUNNABLE,
        BLOCKED,
        WAITING,
        TIMED_WAITING,
        TERMINATED
    }

    @FunctionalInterface
    public interface UncaughtExceptionHandler {
        void uncaughtException(Thread t, Throwable e);
    }
}
