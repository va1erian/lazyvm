package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import lazy.internal.VM;

/**
 * Tasks and workers of a {@link ForkJoinPool} under one monitor. Only one thread runs Java at a
 * time in LazyVM (docs/design/threads.md), so parallelism buys no speed here and workers need no
 * lock-free queues. Parallelism still matters for tasks that block or wait on one another, and one
 * that blocks must never keep a queued task from running.
 *
 * <p>So at most one idle worker, the scout, looks out for queued work the running workers are not
 * getting to. It waits in the VM's own line by yielding, which means it gets a turn at once when a
 * running worker blocks, and otherwise once per time slice. It starts on a task as soon as no other
 * worker can be using the processor. A worker that seems to be using it may be spinning on what a
 * queued task would do, or be in a blocking read, so the scout gives in after a time that doubles
 * with the workers already running: CPU-bound work ends up with few threads, and every wait ends.
 *
 * <p>A joiner runs the awaited task itself if it is still queued anywhere, and nothing else: any
 * other task could be waiting for something lower on its own stack.
 */
final class ForkJoinCore {
    static final int MAX_WORKERS = 32767;
    /** Idle workers end after this long; daemons or not, each is an OS thread with a stack. */
    private static final long IDLE_NANOS = 2_000_000_000L;
    /** How long a scout defers to one running worker; twice that for two, and so on up to the cap. */
    private static final long DEFER_NANOS = 10_000_000L;
    private static final int MAX_DEFER_SHIFT = 4;
    private static final long QUIESCE_POLL_NANOS = 1_000_000L;

    private final ForkJoinPool pool;
    private final int parallelism;
    private final String workerPrefix;
    private final ForkJoinQueues queues;

    private final ArrayDeque<ForkJoinWorkerThread> idle = new ArrayDeque<ForkJoinWorkerThread>();
    /** Workers counted from the start of their thread. Past a failed start they are gone again. */
    private int live;
    /** Worker threads being made by a factory, outside the monitor. */
    private int reserved;
    /** Built by a factory and not started. A constructor that throws leaves one here for good. */
    private int unstarted;
    private int blocked;
    /** Workers in {@code helpQuiesce} with nothing to run: they do not hold the pool from being quiet. */
    private int quiescing;
    private volatile ForkJoinWorkerThread scout;
    private long scoutSince;
    private int workerNumbers;
    volatile boolean shutdown;
    private boolean stop;
    volatile boolean terminating;
    volatile boolean terminated;

    ForkJoinCore(ForkJoinPool pool, int parallelism, boolean asyncMode, String workerPrefix) {
        this.pool = pool;
        this.parallelism = parallelism;
        this.workerPrefix = workerPrefix;
        this.queues = new ForkJoinQueues(asyncMode);
    }

    void pushExternal(ForkJoinTask<?> task) {
        boolean spawn;
        synchronized (this) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            queues.addSubmission(task);
            spawn = signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    void pushLocal(ForkJoinWorkerThread w, ForkJoinTask<?> task) {
        boolean spawn;
        synchronized (this) {
            queues.pushLocal(w, task);
            spawn = signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    /** The monitor is held. */
    private int running() {
        return live - idle.size() - blocked;
    }

    /** The monitor is held. Names a scout if the pool is below its target and has none. */
    private boolean signal() {
        if (scout != null || reserved > 0 || terminating || running() >= parallelism) {
            return false;
        }
        ForkJoinWorkerThread w = idle.peekLast();
        if (w != null) {
            appoint(w);
            LockSupport.unpark(w);
            return false;
        }
        if (live + reserved >= MAX_WORKERS) {
            return false;
        }
        reserved++;
        return true;
    }

    /** The monitor is held. */
    private void appoint(ForkJoinWorkerThread w) {
        scout = w;
        scoutSince = System.nanoTime();
    }

    /** The user's factory runs outside the monitor. What it throws reaches whoever needed a worker. */
    private void createWorker() {
        boolean started = false;
        ForkJoinWorkerThread w = null;
        try {
            w = pool.getFactory().newThread(pool);
            if (w != null) {
                enlist(w);
                w.start();
                started = true;
            }
        } finally {
            synchronized (this) {
                reserved--;
                if (w != null && !started) {
                    forget(w);
                }
                maybeFinish();
            }
        }
    }

    /** A new worker begins as an idle one and the scout, and looks for work when its thread runs. */
    private synchronized void enlist(ForkJoinWorkerThread w) {
        unstarted--;
        live++;
        w.parked = true;
        idle.addLast(w);
        appoint(w);
    }

    /** The monitor is held. Its thread did not start, so it never ran and nothing else knows it. */
    private void forget(ForkJoinWorkerThread w) {
        queues.freeSlot(w.slot());
        live--;
        unlist(w);
    }

    /** The monitor is held. */
    private void unlist(ForkJoinWorkerThread w) {
        if (w.parked) {
            idle.remove(w);
            w.parked = false;
        }
        if (scout == w) {
            scout = null;
        }
    }

    /** Called by a worker's constructor. Its number and slot are taken here. */
    synchronized int register(ForkJoinWorkerThread w) {
        w.setName(workerPrefix + ++workerNumbers);
        unstarted++;
        return queues.takeSlot(w);
    }

    /**
     * A worker killed by a task gets a successor if nobody idle can take its place, work or not, but
     * one whose {@code onStart} failed gets none ("killed worker's forks", "onStart failure: gone").
     */
    void deregister(ForkJoinWorkerThread w, int slot, boolean began, boolean killed) {
        boolean spawn;
        List<ForkJoinTask<?>> dropped = new ArrayList<ForkJoinTask<?>>();
        synchronized (this) {
            queues.freeSlot(slot);
            live--;
            unlist(w);
            // Only a worker killed by a task can have anything queued here, and that is not run.
            queues.drainLocal(w, dropped);
            spawn = !stop && began && (killed || queues.anyQueued()) && signal();
            maybeFinish();
        }
        cancelAll(dropped);
        if (spawn) {
            createWorker();
        }
    }

    void workLoop(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t;
        while ((t = nextForWorker(w)) != null) {
            w.current = t;
            t.run0();
            w.current = null;
        }
    }

    private ForkJoinTask<?> nextForWorker(ForkJoinWorkerThread w) {
        for (;;) {
            ForkJoinTask<?> t;
            boolean spawn = false;
            synchronized (this) {
                if (terminating) {
                    return null;
                }
                t = look(w);
                if (t != null) {
                    if (w.parked) {
                        idle.removeLastOccurrence(w);
                        w.parked = false;
                    }
                    spawn = queues.anyQueued() && signal();
                } else if (!w.parked) {
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

    /**
     * The monitor is held. A worker's own tasks are always its to run, even above the target after
     * a blocked worker came back: nobody else may get to them. Only then does it stay out of the way.
     */
    private ForkJoinTask<?> look(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t = queues.ownNext(w);
        if (t != null) {
            return t;
        }
        if (!w.parked) {
            return running() - 1 < parallelism ? queues.takeForeign(w) : null;
        }
        if (scout != w) {
            return null;
        }
        if (!queues.anyQueued() || running() >= parallelism) {
            scout = null;
            return null;
        }
        if (defers()) {
            return null;
        }
        scout = null;
        return queues.takeForeign(w);
    }

    /** The monitor is held. Whether the scout should give the workers now running more time. */
    private boolean defers() {
        if (running() == 0 || System.nanoTime() - scoutSince >= DEFER_NANOS << Math.min(live - idle.size() - 1, MAX_DEFER_SHIFT)) {
            return false;
        }
        for (ForkJoinWorkerThread other : queues.workers()) {
            if (other != null && !other.parked && mayBeBusy(other)) {
                return true;
            }
        }
        return false;
    }

    /** A short wait for a monitor is part of being busy, and a joiner whose task is done just has not got back. */
    private static boolean mayBeBusy(ForkJoinWorkerThread w) {
        Thread.State state = w.getState();
        ForkJoinTask<?> awaited = w.awaited;
        return state == Thread.State.RUNNABLE || state == Thread.State.BLOCKED || awaited != null && awaited.isDone();
    }

    /** False when the worker stayed idle too long and is off the idle list, so it should end. */
    private boolean parkIdle(ForkJoinWorkerThread w) {
        long deadline = System.nanoTime() + IDLE_NANOS;
        while (w.parked) {
            Thread.interrupted();
            if (scout == w) {
                Thread.yield();
                return true;
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                synchronized (this) {
                    if (w.parked && scout != w) {
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

    /** Whether a task should go on another worker's fresh stack rather than on top of this one. */
    static boolean stackTight() {
        return VM.stackHalfUsed();
    }

    void helpJoin(ForkJoinWorkerThread w, ForkJoinTask<?> awaited) {
        if (stackTight()) {
            return;
        }
        boolean mine;
        synchronized (this) {
            mine = queues.removeQueued(w, awaited);
        }
        if (mine) {
            awaited.run0();
        }
    }

    /** With no workers ever (a common pool of parallelism 0), a waiting outside thread must do it. */
    void helpOutside(ForkJoinTask<?> awaited) {
        boolean mine = false;
        if (parallelism == 0) {
            synchronized (this) {
                mine = queues.removeQueued(null, awaited);
            }
        }
        if (mine) {
            awaited.run0();
        }
    }

    /**
     * Runs what it can find, then waits for the workers to have nothing to do. Blocked workers do
     * not count as doing something, nor do others waiting here ("two in helpQuiesce"), but one that
     * leaves counts again at once: the second stays until the first's task is over.
     */
    void helpQuiesce(ForkJoinWorkerThread w) {
        boolean waiting = false;
        for (;;) {
            ForkJoinTask<?> t = null;
            boolean tight = stackTight();
            synchronized (this) {
                if (!tight) {
                    t = queues.ownNext(w);
                    t = t != null ? t : queues.takeForeign(w);
                }
                if (t != null && waiting) {
                    quiescing--;
                    waiting = false;
                } else if (t == null && !waiting) {
                    quiescing++;
                    waiting = true;
                }
                if (t == null && !queues.anyQueued() && running() == quiescing) {
                    quiescing--;
                    return;
                }
            }
            if (t != null) {
                t.run0();
            } else if (tight) {
                beginBlock();
                LockSupport.parkNanos(this, QUIESCE_POLL_NANOS);
                endBlock();
            } else {
                LockSupport.parkNanos(this, QUIESCE_POLL_NANOS);
            }
        }
    }

    synchronized ForkJoinTask<?> pollForeign() {
        return queues.takeForeign(null);
    }

    void beginBlock() {
        boolean spawn;
        synchronized (this) {
            blocked++;
            spawn = queues.anyQueued() && signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    synchronized void endBlock() {
        blocked--;
    }

    synchronized boolean unfork(ForkJoinWorkerThread w, ForkJoinTask<?> t) {
        return queues.unfork(w, t);
    }

    synchronized int localCount(ForkJoinWorkerThread w) {
        return w.local.size();
    }

    synchronized int surplus(ForkJoinWorkerThread w) {
        return Math.max(0, w.local.size() - idle.size());
    }

    synchronized ForkJoinTask<?> nextLocal(ForkJoinWorkerThread w, boolean remove) {
        return remove ? queues.ownNext(w) : queues.peekOwnNext(w);
    }

    synchronized ForkJoinTask<?> pollAny(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t = queues.ownNext(w);
        return t != null ? t : queues.takeForeign(w);
    }

    synchronized int poolSize() {
        return live;
    }

    synchronized int runningCount() {
        return running();
    }

    synchronized int active() {
        return live - idle.size();
    }

    /** A scout still in place has queued work it is about to start on. */
    synchronized boolean isQuiescent() {
        return live + reserved == idle.size() && (scout == null || !queues.anyQueued());
    }

    synchronized long steals() {
        return queues.steals();
    }

    synchronized long queuedTasks() {
        return queues.localTasks();
    }

    synchronized int queuedSubmissions() {
        return queues.submissions();
    }

    synchronized ForkJoinTask<?> pollSubmission() {
        return queues.pollSubmission();
    }

    /** The bracket of the pool's {@code toString}. */
    synchronized String describe() {
        String word = terminated ? "Terminated" : terminating ? "Terminating" : shutdown ? "Shutting down" : "Running";
        return "[" + word + ", parallelism = " + parallelism + ", size = " + live + ", active = "
                + (live - idle.size()) + ", running = " + running() + ", steals = " + queues.steals()
                + ", tasks = " + queues.localTasks() + ", submissions = " + queues.submissions() + "]";
    }

    synchronized void shutdown() {
        shutdown = true;
        maybeFinish();
    }

    /** Ends the pool now, cancelling what is queued, running or awaited. */
    void stop() {
        List<ForkJoinTask<?>> dropped = new ArrayList<ForkJoinTask<?>>();
        List<ForkJoinWorkerThread> toInterrupt = new ArrayList<ForkJoinWorkerThread>();
        synchronized (this) {
            shutdown = true;
            stop = true;
            queues.drainAll(dropped);
            for (ForkJoinWorkerThread w : queues.workers()) {
                if (w != null) {
                    if (w.current != null) {
                        dropped.add(w.current);
                    }
                    if (w.awaited != null) {
                        dropped.add(w.awaited);
                    }
                    toInterrupt.add(w);
                }
            }
            beginTermination();
            maybeFinish();
        }
        // In this order: a running task that looks finds itself cancelled first ("cancelled first").
        cancelAll(dropped);
        for (ForkJoinWorkerThread w : toInterrupt) {
            w.interrupt();
        }
    }

    /** Outside the monitor, as {@code cancel} is the user's to override. One that throws stops no other. */
    private static void cancelAll(List<ForkJoinTask<?>> tasks) {
        for (ForkJoinTask<?> t : tasks) {
            try {
                t.cancel(false);
            } catch (Throwable theOthersMustStillBeCancelled) {
                continue;
            }
        }
    }

    /** The monitor is held. */
    private void beginTermination() {
        terminating = true;
        scout = null;
        for (ForkJoinWorkerThread w : idle) {
            w.parked = false;
            LockSupport.unpark(w);
        }
        idle.clear();
    }

    /** The monitor is held. To be called whenever a worker went idle or away, or on shutdown. */
    private void maybeFinish() {
        if (shutdown && !terminating && live == idle.size() && !queues.anyQueued()) {
            beginTermination();
        }
        if (terminating && !terminated && live == 0 && reserved == 0 && unstarted == 0) {
            terminated = true;
            notifyAll();
        }
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
