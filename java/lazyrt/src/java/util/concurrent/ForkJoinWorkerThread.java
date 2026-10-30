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
    /** Parked and listed as idle. */
    volatile boolean parked;
    /** Told to look for work, and not yet arrived. */
    boolean woken;

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
        try {
            onStart();
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
                core.deregister(this, index);
            }
        }
        if (failure != null) {
            // On to this thread's uncaught handler, which is the pool's when it has one.
            ForkJoinTask.<RuntimeException>rethrow(failure);
        }
    }
}
