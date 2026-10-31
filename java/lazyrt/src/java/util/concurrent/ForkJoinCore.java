package java.util.concurrent;

import java.util.concurrent.locks.LockSupport;

/**
 * What the workers of a {@link ForkJoinPool} do with tasks; {@link ForkJoinRoster}, the same object
 * and monitor, says who they are. Only one thread runs Java at a time in LazyVM
 * (docs/design/threads.md), so parallelism buys no speed here and workers need no lock-free queues.
 * Parallelism still matters for tasks that block or wait on one another, and one that blocks must
 * never keep a queued task from running.
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
final class ForkJoinCore extends ForkJoinRoster {
    /** Idle workers end after this long; daemons or not, each is an OS thread with a stack. */
    private static final long IDLE_NANOS = 2_000_000_000L;
    /** How long a scout defers to one running worker; twice that for two, and so on up to the cap. */
    private static final long DEFER_NANOS = 10_000_000L;
    private static final int MAX_DEFER_SHIFT = 4;
    private static final long POLL_NANOS = 1_000_000L;

    /** Workers in {@code helpQuiesce} with nothing to run: they do not hold the pool from being quiet. */
    private int quiescing;

    ForkJoinCore(ForkJoinPool pool, int parallelism, boolean asyncMode, String workerPrefix) {
        super(pool, parallelism, asyncMode, workerPrefix);
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
                    // A scout that takes what its onStart forked has not given up the post yet.
                    if (scout == w) {
                        scout = null;
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
     * The monitor is held. A worker that just finished something goes on, even above the target after
     * a blocked worker came back: only an idle one is held to the target ("queue served above the target").
     */
    private ForkJoinTask<?> look(ForkJoinWorkerThread w) {
        ForkJoinTask<?> t = queues.ownNext(w);
        if (t != null) {
            return t;
        }
        if (!w.parked) {
            return queues.takeForeign(w);
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
        if (running() == 0 || System.nanoTime() - scoutSince >= DEFER_NANOS << Math.min(busyWorkers() - 1, MAX_DEFER_SHIFT)) {
            return false;
        }
        for (ForkJoinWorkerThread other : queues.workers()) {
            if (other != null && !other.parked && mayBeBusy(other)) {
                return true;
            }
        }
        return false;
    }

    /** A short wait for a monitor is part of being busy, and a joiner whose task is done is on its way back. */
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

    /** Whether a worker about to wait for {@code awaited} should run it now instead. */
    synchronized boolean takeToJoin(ForkJoinWorkerThread w, ForkJoinTask<?> awaited) {
        return queues.removeQueued(w, awaited);
    }

    /** With no workers ever (a common pool of parallelism 0), a waiting outside thread must do it. */
    boolean takeOutside(ForkJoinTask<?> awaited) {
        if (parallelism != 0) {
            return false;
        }
        synchronized (this) {
            return queues.removeQueued(null, awaited);
        }
    }

    /** For an outside thread waiting for quiet: a queued task no worker is on its way to. */
    synchronized ForkJoinTask<?> takeForAssist() {
        return scout == null && reserved == 0 ? queues.takeForeign(null) : null;
    }

    /**
     * Runs what it can find, then waits for the workers to have nothing to do. Blocked workers do
     * not count as doing something, nor do others waiting here ("two in helpQuiesce"), but one that
     * leaves counts again at once: the second stays until the first's task is over. Nothing called
     * with its count taken goes deeper than the look that came first, so a stack that ends here
     * cannot leave the count behind ("helpQuiesce beside a busy worker").
     */
    void helpQuiesce(ForkJoinWorkerThread w) {
        boolean waiting = false;
        for (;;) {
            ForkJoinTask<?> t;
            synchronized (this) {
                t = queues.ownNext(w);
                t = t != null ? t : queues.takeForeign(w);
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
            } else {
                LockSupport.parkNanos(this, POLL_NANOS);
            }
        }
    }
}
