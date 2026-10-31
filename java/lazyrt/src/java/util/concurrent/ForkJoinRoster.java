package java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Who is in a {@link ForkJoinPool}, and the pool's run state, under one monitor: the workers and
 * what they count as, the scout, the making of a worker, and the way to termination. What the
 * workers do with tasks is in {@link ForkJoinCore}.
 */
abstract class ForkJoinRoster {
    static final int MAX_WORKERS = 32767;

    private final ForkJoinPool pool;
    final int parallelism;
    private final String workerPrefix;
    final ForkJoinQueues queues;

    final ArrayDeque<ForkJoinWorkerThread> idle = new ArrayDeque<ForkJoinWorkerThread>();
    /** Workers counted from the start of their thread. Past a failed start they are gone again. */
    private int live;
    /** Worker threads being made by a factory, outside the monitor. */
    int reserved;
    /** Built and not started. A constructor that throws leaves one here for good. */
    private int unstarted;
    private int blocked;
    volatile ForkJoinWorkerThread scout;
    long scoutSince;
    private int workerNumbers;
    volatile boolean shutdown;
    private boolean stop;
    volatile boolean terminating;
    volatile boolean terminated;

    ForkJoinRoster(ForkJoinPool pool, int parallelism, boolean asyncMode, String workerPrefix) {
        this.pool = pool;
        this.parallelism = parallelism;
        this.workerPrefix = workerPrefix;
        this.queues = new ForkJoinQueues(this, asyncMode);
    }

    /** The monitor is held. */
    final int running() {
        return live - idle.size() - blocked;
    }

    /** The monitor is held. Workers not idle, whether running or blocked. */
    final int busyWorkers() {
        return live - idle.size();
    }

    /** The monitor is held. Names a scout if the pool is below its target and has none. */
    final boolean signal() {
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

    /**
     * The user's factory runs outside the monitor. What it throws reaches whoever needed a worker.
     * The worker it gives may have been built for another pool: it is that pool's to count.
     */
    final void createWorker() {
        boolean started = false;
        ForkJoinWorkerThread w = null;
        try {
            w = pool.getFactory().newThread(pool);
            if (w != null) {
                w.core.enlist(w);
                w.start();
                started = true;
            }
        } finally {
            // A thread the factory started itself makes start throw, and is a worker all the same.
            if (w != null && !started && w.getState() == Thread.State.NEW) {
                w.core.forget(w);
            }
            synchronized (this) {
                reserved--;
                maybeFinish();
            }
        }
    }

    /**
     * A new worker begins as an idle one and the scout, and looks for work when its thread runs.
     * Its thread calls this too, for a worker the user started without the pool asking.
     */
    final synchronized void enlist(ForkJoinWorkerThread w) {
        if (w.enlisted) {
            return;
        }
        w.enlisted = true;
        unstarted--;
        live++;
        w.parked = true;
        idle.addLast(w);
        appoint(w);
    }

    /** Its thread did not start, so it never ran and nothing else knows it. */
    final synchronized void forget(ForkJoinWorkerThread w) {
        queues.freeSlot(w.slot());
        live--;
        unlist(w);
        maybeFinish();
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
    final synchronized int register(ForkJoinWorkerThread w) {
        w.setName(workerPrefix + ++workerNumbers);
        unstarted++;
        return queues.takeSlot(w);
    }

    /**
     * A worker killed by a task gets a successor if nobody idle can take its place, work or not, but
     * one whose {@code onStart} failed gets none ("killed worker's forks", "onStart failure: gone").
     */
    final void deregister(ForkJoinWorkerThread w, int slot, boolean began, boolean killed) {
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
        ForkJoinQueues.cancelAll(dropped);
        if (spawn) {
            createWorker();
        }
    }

    /** A blocker inside a blocker is still one worker blocked ("nested blockers"). */
    final void beginBlock(ForkJoinWorkerThread w) {
        if (w.blockDepth++ > 0) {
            return;
        }
        boolean spawn;
        synchronized (this) {
            blocked++;
            spawn = queues.anyQueued() && signal();
        }
        if (spawn) {
            createWorker();
        }
    }

    final void endBlock(ForkJoinWorkerThread w) {
        if (--w.blockDepth == 0) {
            synchronized (this) {
                blocked--;
            }
        }
    }

    final synchronized int surplus(ForkJoinWorkerThread w) {
        return Math.max(0, w.local.size() - idle.size());
    }

    final synchronized int poolSize() {
        return live;
    }

    final synchronized int runningCount() {
        return running();
    }

    final synchronized int active() {
        return busyWorkers();
    }

    /** A scout still in place has queued work it is about to start on. */
    final synchronized boolean isQuiescent() {
        return live + reserved == idle.size() && (scout == null || !queues.anyQueued());
    }

    /** The bracket of the pool's {@code toString}. */
    final synchronized String describe() {
        String word = terminated ? "Terminated" : terminating ? "Terminating" : shutdown ? "Shutting down" : "Running";
        return "[" + word + ", parallelism = " + parallelism + ", size = " + live + ", active = "
                + busyWorkers() + ", running = " + running() + ", steals = " + queues.steals()
                + ", tasks = " + queues.localTasks() + ", submissions = " + queues.submissions() + "]";
    }

    final synchronized void shutdown() {
        shutdown = true;
        maybeFinish();
    }

    /** Ends the pool now, cancelling what is queued, running or awaited. */
    final void stop() {
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
        // In this order, as on the oracle a running task usually finds itself cancelled first.
        ForkJoinQueues.cancelAll(dropped);
        for (ForkJoinWorkerThread w : toInterrupt) {
            w.interrupt();
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
    final void maybeFinish() {
        if (shutdown && !terminating && live == idle.size() && !queues.anyQueued()) {
            beginTermination();
        }
        if (terminating && !terminated && live == 0 && reserved == 0 && unstarted == 0) {
            terminated = true;
            notifyAll();
        }
    }

    /** False if the deadline came first. */
    final synchronized boolean awaitTerminated(long deadline) throws InterruptedException {
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
