package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Tasks and workers of a {@link ForkJoinPool} under one monitor. Only one thread runs Java at a
 * time in LazyVM (docs/design/threads.md), so parallelism buys no speed here and workers need no
 * lock-free queues: every worker has a plain deque, others take from its old end when they run
 * dry, and outside threads add to a list of submissions. Parallelism still matters for tasks that
 * block, so a worker that blocks in a join or a {@code ManagedBlocker} stops counting, and another
 * may then take its place. A joiner runs the awaited task itself if it is still queued anywhere,
 * then its own tasks. It takes no others: one of those could be waiting for something lower on
 * its own stack.
 */
final class ForkJoinCore {
    static final int MAX_WORKERS = 32767;
    /** Idle workers end after this long; daemons or not, each is an OS thread with a stack. */
    private static final long IDLE_NANOS = 2_000_000_000L;

    private final ForkJoinPool pool;
    private final int parallelism;
    private final boolean asyncMode;
    private final String workerPrefix;

    private final ArrayDeque<ForkJoinTask<?>> submissions = new ArrayDeque<ForkJoinTask<?>>();
    /** By pool index; a slot is empty once its worker ended. */
    private final ArrayList<ForkJoinWorkerThread> workers = new ArrayList<ForkJoinWorkerThread>();
    private final ArrayDeque<ForkJoinWorkerThread> idle = new ArrayDeque<ForkJoinWorkerThread>();
    private int live;
    private int reserved;
    private int blocked;
    /** Workers told to look for work that have not looked yet; one at a time is enough. */
    private int waking;
    private int workerNumbers;
    private long steals;
    private boolean shutdown;
    private boolean stop;
    private boolean terminating;
    private boolean terminated;

    ForkJoinCore(ForkJoinPool pool, int parallelism, boolean asyncMode, String workerPrefix) {
        this.pool = pool;
        this.parallelism = parallelism;
        this.asyncMode = asyncMode;
        this.workerPrefix = workerPrefix;
    }

    void pushExternal(ForkJoinTask<?> task) {
        boolean spawn;
        synchronized (this) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            submissions.addLast(task);
            spawn = signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    void pushLocal(ForkJoinWorkerThread w, ForkJoinTask<?> task) {
        boolean spawn = false;
        boolean dropped;
        synchronized (this) {
            dropped = stop;
            if (!dropped) {
                w.local.addLast(task);
                spawn = signal();
            }
        }
        if (dropped) {
            task.cancel(false);
        } else if (spawn) {
            createWorker();
        }
    }

    /** The monitor is held. Wakes or orders one more worker if the pool is below its target. */
    private boolean signal() {
        if (waking > 0 || terminating || live + reserved - idle.size() - blocked >= parallelism) {
            return false;
        }
        ForkJoinWorkerThread w = idle.pollLast();
        if (w != null) {
            w.parked = false;
            w.woken = true;
            waking++;
            LockSupport.unpark(w);
            return false;
        }
        if (live + reserved >= MAX_WORKERS) {
            return false;
        }
        reserved++;
        waking++;
        return true;
    }

    /** The user's factory runs outside the monitor. What it throws reaches whoever needed a worker. */
    private void createWorker() {
        boolean started = false;
        try {
            ForkJoinWorkerThread w = pool.getFactory().newThread(pool);
            if (w != null) {
                synchronized (this) {
                    w.woken = true;
                }
                w.start();
                started = true;
            }
        } finally {
            synchronized (this) {
                reserved--;
                if (!started) {
                    waking--;
                }
            }
        }
    }

    /** Called by a worker's constructor. Its number and slot are taken here. */
    synchronized int register(ForkJoinWorkerThread w) {
        w.setName(workerPrefix + ++workerNumbers);
        live++;
        int slot = workers.indexOf(null);
        if (slot < 0) {
            workers.add(w);
            return workers.size() - 1;
        }
        workers.set(slot, w);
        return slot;
    }

    void deregister(ForkJoinWorkerThread w, int slot) {
        boolean spawn;
        synchronized (this) {
            workers.set(slot, null);
            live--;
            if (w.woken) {
                w.woken = false;
                waking--;
            }
            // What it forked and nobody took must not be lost with it.
            submissions.addAll(w.local);
            w.local.clear();
            spawn = !stop && anyQueued() && signal();
            maybeFinish();
        }
        if (spawn) {
            createWorker();
        }
    }

    void workLoop(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t;
        while ((t = nextForWorker(w)) != null) {
            // A task's leftover interrupt must not reach the next one.
            Thread.interrupted();
            t.run0();
        }
    }

    private ForkJoinTask<?> nextForWorker(ForkJoinWorkerThread w) {
        for (;;) {
            ForkJoinTask<?> t;
            boolean spawn = false;
            synchronized (this) {
                if (w.woken) {
                    w.woken = false;
                    waking--;
                }
                if (terminating) {
                    return null;
                }
                // Above target after a blocked worker came back, this one stays out of the way.
                t = live - idle.size() - blocked <= parallelism ? take(w) : null;
                if (t != null) {
                    spawn = anyQueued() && signal();
                } else {
                    w.parked = true;
                    idle.addLast(w);
                    maybeFinish();
                }
            }
            if (spawn) {
                createWorker();
            }
            if (t != null) {
                return t;
            }
            if (!parkIdle(w)) {
                return null;
            }
        }
    }

    /** False when the worker stayed idle too long and is off the idle list, so it should end. */
    private boolean parkIdle(ForkJoinWorkerThread w) {
        long deadline = System.nanoTime() + IDLE_NANOS;
        while (w.parked) {
            Thread.interrupted();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                synchronized (this) {
                    if (w.parked) {
                        idle.remove(w);
                        w.parked = false;
                        return false;
                    }
                }
                return true;
            }
            LockSupport.parkNanos(this, left);
        }
        return true;
    }

    private ForkJoinTask<?> ownNext(ForkJoinWorkerThread w) {
        return asyncMode ? w.local.pollFirst() : w.local.pollLast();
    }

    /** The monitor is held. Own tasks first, then the oldest of another's, then a submission. */
    private ForkJoinTask<?> take(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t = ownNext(w);
        return t != null ? t : takeForeign(w);
    }

    private ForkJoinTask<?> takeForeign(ForkJoinWorkerThread w) {
        for (ForkJoinWorkerThread other : workers) {
            if (other != null && other != w) {
                ForkJoinTask<?> t = other.local.pollFirst();
                if (t != null) {
                    steals++;
                    return t;
                }
            }
        }
        return submissions.pollFirst();
    }

    /** The monitor is held. */
    private boolean anyQueued() {
        return !submissions.isEmpty() || localCount() > 0;
    }

    /** The monitor is held. */
    private int localCount() {
        int n = 0;
        for (ForkJoinWorkerThread w : workers) {
            if (w != null) {
                n += w.local.size();
            }
        }
        return n;
    }

    void helpJoin(ForkJoinWorkerThread w, ForkJoinTask<?> awaited) {
        while (!awaited.isDone()) {
            ForkJoinTask<?> next;
            synchronized (this) {
                next = removeQueued(w, awaited) ? awaited : ownNext(w);
            }
            if (next == null) {
                return;
            }
            next.run0();
        }
    }

    /** The monitor is held. Looks at the likely places first: this worker's newest, then oldest. */
    private boolean removeQueued(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        if (w != null) {
            if (w.local.peekLast() == t) {
                w.local.pollLast();
                return true;
            }
            if (w.local.removeFirstOccurrence(t)) {
                return true;
            }
        }
        for (ForkJoinWorkerThread other : workers) {
            if (other != null && other != w && other.local.removeFirstOccurrence(t)) {
                return true;
            }
        }
        return submissions.removeFirstOccurrence(t);
    }

    /** Runs the task in the caller if it is still queued here; for outside threads and the common pool. */
    void helpExternal(ForkJoinTask<?> awaited) {
        boolean mine;
        synchronized (this) {
            mine = removeQueued(null, awaited);
        }
        if (mine) {
            awaited.run0();
        }
    }

    void helpQuiesce(ForkJoinWorkerThread w) {
        for (;;) {
            ForkJoinTask<?> t;
            synchronized (this) {
                t = take(w);
                if (t == null && live - idle.size() <= 1) {
                    return;
                }
            }
            if (t != null) {
                t.run0();
            } else {
                Thread.yield();
            }
        }
    }

    /** Runs one queued task in an outside thread; false when there is none. */
    boolean runOneQueued() {
        ForkJoinTask<?> t = pollForeign();
        if (t == null) {
            return false;
        }
        t.run0();
        return true;
    }

    synchronized ForkJoinTask<?> pollForeign() {
        return takeForeign(null);
    }

    void beginBlock() {
        boolean spawn;
        synchronized (this) {
            blocked++;
            spawn = anyQueued() && signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    synchronized void endBlock() {
        blocked--;
    }

    synchronized boolean unfork(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        ArrayDeque<ForkJoinTask<?>> q = w != null ? w.local : submissions;
        if (q.peekLast() != t) {
            return false;
        }
        q.pollLast();
        return true;
    }

    synchronized int localCount(ForkJoinWorkerThread w) {
        return w.local.size();
    }

    synchronized int surplus(ForkJoinWorkerThread w) {
        return Math.max(0, w.local.size() - idle.size());
    }

    synchronized ForkJoinTask<?> nextLocal(ForkJoinWorkerThread w, boolean remove) {
        if (remove) {
            return ownNext(w);
        }
        return asyncMode ? w.local.peekFirst() : w.local.peekLast();
    }

    synchronized ForkJoinTask<?> pollAny(ForkJoinWorkerThread w) {
        return take(w);
    }

    synchronized int poolSize() {
        return live;
    }

    synchronized int running() {
        return live - idle.size() - blocked;
    }

    synchronized int active() {
        return live - idle.size();
    }

    synchronized boolean isQuiescent() {
        return live == idle.size() && !anyQueued();
    }

    synchronized long steals() {
        return steals;
    }

    synchronized long queuedTasks() {
        return localCount();
    }

    synchronized int queuedSubmissions() {
        return submissions.size();
    }

    synchronized ForkJoinTask<?> pollSubmission() {
        return submissions.pollFirst();
    }

    /** The bracket of the pool's {@code toString}. */
    synchronized String describe() {
        String word = terminated ? "Terminated" : terminating ? "Terminating" : shutdown ? "Shutting down" : "Running";
        return "[" + word + ", parallelism = " + parallelism + ", size = " + live + ", active = "
                + (live - idle.size()) + ", running = " + (live - idle.size() - blocked) + ", steals = " + steals
                + ", tasks = " + localCount() + ", submissions = " + submissions.size() + "]";
    }

    synchronized void shutdown() {
        shutdown = true;
        maybeFinish();
    }

    /** Ends the pool now; the tasks still queued, for the caller to cancel. */
    List<ForkJoinTask<?>> stop() {
        List<ForkJoinTask<?>> dropped = new ArrayList<ForkJoinTask<?>>();
        synchronized (this) {
            shutdown = true;
            stop = true;
            dropped.addAll(submissions);
            submissions.clear();
            for (ForkJoinWorkerThread w : workers) {
                if (w != null) {
                    dropped.addAll(w.local);
                    w.local.clear();
                    w.interrupt();
                }
            }
            beginTermination();
            maybeFinish();
        }
        return dropped;
    }

    /** The monitor is held. */
    private void beginTermination() {
        terminating = true;
        for (ForkJoinWorkerThread w : idle) {
            w.parked = false;
            LockSupport.unpark(w);
        }
        idle.clear();
    }

    /** The monitor is held. To be called whenever a worker went idle or away, or on shutdown. */
    private void maybeFinish() {
        if (shutdown && !terminating && live == idle.size() && !anyQueued()) {
            beginTermination();
        }
        if (terminating && !terminated && live == 0 && reserved == 0) {
            terminated = true;
            notifyAll();
        }
    }

    synchronized boolean isTerminated() {
        return terminated;
    }

    synchronized boolean isTerminating() {
        return terminating && !terminated;
    }

    synchronized boolean isShutdown() {
        return shutdown;
    }

    /** False if the deadline came first. */
    synchronized boolean awaitTerminated(long deadline) throws InterruptedException {
        while (!terminated) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
}
