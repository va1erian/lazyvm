package java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import lazy.internal.Turnstile;

/**
 * The workers and run state of a {@link ThreadPoolExecutor}. One monitor guards the state, the
 * sizes and the worker list; the queue may be the user's, so it is never called with the monitor
 * held. A worker is marked busy under the monitor, which also decides its interrupt flag then, so
 * a {@code shutdown} that interrupts idle workers cannot reach a task that just began.
 */
final class PoolWorkers {
    // In the order a pool passes through them. Comparisons rely on that.
    static final int RUNNING = 0;
    static final int SHUTDOWN = 1;
    static final int STOP = 2;
    static final int TERMINATING = 3;
    static final int TERMINATED = 4;

    /** A last worker waiting on a queue with entries not yet due must not spin at keep-alive zero. */
    private static final long MIN_LAST_WORKER_WAIT = 5_000_000L;

    final BlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor owner;
    private final List<Worker> workers = new ArrayList<Worker>();
    volatile int state = RUNNING;
    /** Slots taken while a thread is being made, outside the monitor. */
    private int starting;
    /** Threads in {@code shutdownNow} emptying the queue: the pool does not end before they are done. */
    private int draining;
    private int busy;
    private int largest;
    private long completed;
    volatile int core;
    volatile int max;
    volatile long keepAliveNanos;
    volatile boolean coreTimesOut;
    volatile ThreadFactory factory;

    PoolWorkers(ThreadPoolExecutor owner, BlockingQueue<Runnable> queue) {
        this.owner = owner;
        this.queue = queue;
    }

    private final class Worker implements Runnable {
        Thread thread;
        Runnable first;
        boolean busy;

        Worker(Runnable first) {
            this.first = first;
        }

        @Override
        public void run() {
            workLoop(this);
        }
    }

    /** Starts a worker if the state and the limit allow it; false also if the factory gave no thread. */
    boolean startWorker(Runnable first, boolean withinCore) {
        if (state >= TERMINATING) {
            return false;
        }
        // After shutdown a worker only comes for what is left in the queue.
        boolean noWork = state == SHUTDOWN && (first != null || queue.isEmpty());
        synchronized (this) {
            boolean open = state == RUNNING || state == SHUTDOWN && !noWork;
            if (!open || workers.size() + starting >= (withinCore ? core : max)) {
                return false;
            }
            starting++;
        }
        Worker w = new Worker(first);
        boolean started = false;
        try {
            w.thread = factory.newThread(w);
            started = w.thread != null && register(w);
        } finally {
            if (!started) {
                abandon();
            }
        }
        return started;
    }

    /** Lists the worker and starts its thread, unless the pool stopped meanwhile. */
    private boolean register(Worker w) {
        synchronized (this) {
            if (state >= STOP || state == SHUTDOWN && w.first != null) {
                return false;
            }
            starting--;
            workers.add(w);
            largest = Math.max(largest, workers.size());
            // Busy until it looks for work itself, so an interrupt meant for idle ones cannot find
            // it on its way there and stay set through its task or the terminated hook.
            w.busy = true;
            busy++;
        }
        try {
            w.thread.start();
        } catch (RuntimeException | Error e) {
            synchronized (this) {
                workers.remove(w);
                busy--;
                starting++;
            }
            throw e;
        }
        return true;
    }

    private void abandon() {
        synchronized (this) {
            starting--;
        }
        endIfDone();
    }

    private void workLoop(Worker w) {
        boolean abrupt = true;
        try {
            Runnable task = w.first;
            w.first = null;
            if (task == null) {
                end(w, false);
            }
            while (task != null || (task = nextTask(w)) != null) {
                runTask(w, task);
                task = null;
            }
            abrupt = false;
        } finally {
            workerExit(w, abrupt);
        }
    }

    private void runTask(Worker w, Runnable task) {
        begin(w);
        try {
            owner.beforeExecute(w.thread, task);
            Throwable thrown = null;
            try {
                task.run();
            } catch (RuntimeException | Error t) {
                thrown = t;
                throw t;
            } catch (Throwable t) {
                // Only code hiding a checked exception gets here.
                thrown = t;
                throw new Error(t);
            } finally {
                owner.afterExecute(task, thrown);
            }
        } finally {
            end(w, true);
        }
    }

    private synchronized void begin(Worker w) {
        if (!w.busy) {
            w.busy = true;
            busy++;
        }
        // A task sees the interrupt of a stop, and none left over from before.
        if (state >= STOP) {
            Thread.currentThread().interrupt();
        } else {
            Thread.interrupted();
        }
    }

    private synchronized void end(Worker w, boolean ranTask) {
        w.busy = false;
        busy--;
        if (ranTask) {
            completed++;
        }
    }

    /**
     * The next task, or null when this worker should end. It then leaves the list at once, under
     * the same monitor as the decision, so two workers timing out together cannot both go.
     */
    private Runnable nextTask(Worker w) {
        boolean timedOut = false;
        for (;;) {
            boolean empty = state >= STOP || queue.isEmpty();
            boolean timed;
            long wait;
            synchronized (this) {
                int size = workers.size();
                timed = coreTimesOut || size > core;
                if (state >= STOP || state == SHUTDOWN && empty
                        || size > max || timedOut && timed && (size > 1 || empty)) {
                    workers.remove(w);
                    return null;
                }
                wait = size > 1 || empty ? keepAliveNanos : Math.max(keepAliveNanos, MIN_LAST_WORKER_WAIT);
            }
            try {
                Runnable r = timed ? queue.poll(wait, TimeUnit.NANOSECONDS) : queue.take();
                if (r != null) {
                    return r;
                }
                timedOut = true;
            } catch (InterruptedException wokenToLookAgain) {
                timedOut = false;
            }
        }
    }

    private void workerExit(Worker w, boolean abrupt) {
        int size;
        synchronized (this) {
            workers.remove(w);
            size = workers.size();
        }
        endIfDone();
        if (state >= STOP) {
            return;
        }
        // A worker that died from a task's throw is replaced at once; one that retired only if
        // the queue would otherwise have nobody to serve it.
        if (abrupt) {
            startWorker(null, false);
        } else if (size < (coreTimesOut ? 0 : core) || size == 0 && !queue.isEmpty()) {
            startWorker(null, false);
        }
    }

    private List<Runnable> drain() {
        List<Runnable> left = new ArrayList<Runnable>();
        queue.drainTo(left);
        // A delay queue only gives up what is due, and a user's queue may refuse too.
        if (!queue.isEmpty()) {
            for (Runnable r : queue.toArray(new Runnable[0])) {
                if (queue.remove(r)) {
                    left.add(r);
                }
            }
        }
        return left;
    }

    /** The monitor is held. An interrupt only makes a waiting worker look at the state again. */
    private void interruptIdle() {
        for (Worker w : workers) {
            if (!w.busy) {
                w.thread.interrupt();
            }
        }
    }

    /** The monitor is held. */
    private void interruptOneIdle() {
        for (Worker w : workers) {
            if (!w.busy) {
                w.thread.interrupt();
                return;
            }
        }
    }

    /** To be called after anything that may have ended the pool's last work. */
    void endIfDone() {
        if (state == RUNNING) {
            return;
        }
        // Past a stop the queue no longer matters, even if asking it would throw.
        boolean empty = state >= STOP || queue.isEmpty();
        synchronized (this) {
            if (state >= TERMINATING || state == SHUTDOWN && !empty) {
                return;
            }
            if (!workers.isEmpty() || starting > 0 || draining > 0) {
                // Someone may be waiting on a queue that was emptied by a remove. One is enough:
                // it leaves and comes back here, which wakes the next.
                if (empty) {
                    interruptOneIdle();
                }
                return;
            }
            state = TERMINATING;
            // Under the monitor: while the hook runs, other threads wait to read the pool's counts.
            try {
                owner.terminated();
            } finally {
                state = TERMINATED;
                notifyAll();
            }
        }
    }

    synchronized void markShutdown() {
        if (state < SHUTDOWN) {
            state = SHUTDOWN;
        }
        interruptIdle();
    }

    List<Runnable> stop() {
        synchronized (this) {
            if (state < STOP) {
                state = STOP;
            }
            for (Worker w : workers) {
                w.thread.interrupt();
            }
            draining++;
        }
        try {
            return drain();
        } finally {
            synchronized (this) {
                draining--;
            }
            endIfDone();
        }
    }

    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = Turnstile.deadline(unit.toNanos(timeout));
        synchronized (this) {
            while (state != TERMINATED) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return true;
        }
    }

    void setCore(int corePoolSize) {
        if (state >= TERMINATING) {
            core = corePoolSize;
            return;
        }
        int raised;
        synchronized (this) {
            raised = corePoolSize - core;
            core = corePoolSize;
            if (workers.size() > corePoolSize) {
                interruptIdle();
            }
        }
        // New core workers start only as far as there is queued work for them.
        for (int k = Math.min(raised, queue.size()); k > 0 && !queue.isEmpty() && startWorker(null, true); k--) {
            continue;
        }
    }

    synchronized void setMax(int maximumPoolSize) {
        max = maximumPoolSize;
        if (workers.size() > maximumPoolSize) {
            interruptIdle();
        }
    }

    synchronized void setCoreTimesOut(boolean value) {
        if (value != coreTimesOut) {
            coreTimesOut = value;
            if (value) {
                interruptIdle();
            }
        }
    }

    synchronized void setKeepAlive(long nanos) {
        boolean shorter = nanos < keepAliveNanos;
        keepAliveNanos = nanos;
        if (shorter) {
            interruptIdle();
        }
    }

    synchronized int size() {
        return workers.size();
    }

    synchronized int busy() {
        return busy;
    }

    synchronized int largest() {
        return largest;
    }

    synchronized long completed() {
        return completed;
    }
}
