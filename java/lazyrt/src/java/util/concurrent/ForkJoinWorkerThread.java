package java.util.concurrent;

import java.util.ArrayDeque;

/**
 * A pool's worker. Its deque, its idle state and the flags below belong to the pool and are only
 * touched under the pool's monitor.
 */
public class ForkJoinWorkerThread extends Thread {
    final ForkJoinCore core;
    final ArrayDeque<ForkJoinTask<?>> local = new ArrayDeque<ForkJoinTask<?>>();
    private final ForkJoinPool pool;
    private final int index;
    /** The task its work loop is running, and the one it blocks on, for {@code shutdownNow} to cancel. */
    volatile ForkJoinTask<?> current;
    volatile ForkJoinTask<?> awaited;
    /** Listed as idle. */
    volatile boolean parked;
    /** Counted as live by the pool, whoever started the thread. */
    boolean enlisted;
    /** How many blockers it is inside; its own thread's alone. */
    int blockDepth;

    protected ForkJoinWorkerThread(ForkJoinPool pool) {
        super((ThreadGroup) null, (Runnable) null, "ForkJoinWorker");
        this.pool = pool;
        this.core = pool.core;
        setDaemon(true);
        Thread.UncaughtExceptionHandler handler = pool.getUncaughtExceptionHandler();
        if (handler != null) {
            setUncaughtExceptionHandler(handler);
        }
        this.index = core.register(this);
    }

    int slot() {
        return index;
    }

    /** The calling thread if it is a worker of any pool, else null. */
    static ForkJoinWorkerThread current() {
        Thread t = Thread.currentThread();
        return t instanceof ForkJoinWorkerThread ? (ForkJoinWorkerThread) t : null;
    }

    public ForkJoinPool getPool() {
        return pool;
    }

    public int getPoolIndex() {
        return index;
    }

    protected void onStart() {
    }

    protected void onTermination(Throwable exception) {
    }

    public void run() {
        Throwable failure = null;
        boolean began = false;
        try {
            core.enlist(this);
            onStart();
            began = true;
            core.workLoop(this);
        } catch (Throwable t) {
            failure = t;
        } finally {
            try {
                onTermination(failure);
            } catch (Throwable t) {
                if (failure == null) {
                    failure = t;
                }
            } finally {
                core.deregister(this, index, began, failure != null);
            }
        }
        if (failure != null) {
            // On to this thread's uncaught handler, which is the pool's when it has one.
            ForkJoinTask.<RuntimeException>sneak(failure);
        }
    }
}
