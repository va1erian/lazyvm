package java.util.concurrent;

import java.util.concurrent.locks.LockSupport;
import lazy.internal.VM;

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
 *
 * <p>A stack here holds fewer frames than the deep chains people write would like, so past half of
 * it a thread leaves the task for a new stack and waits. Only a few such stacks are added, and when
 * they are all in use, or no worker can come, it runs the task on its own stack after all, where
 * runaway recursion then overflows as it should.
 */
final class ForkJoinCore extends ForkJoinRoster {
    /** Idle workers end after this long; daemons or not, each is an OS thread with a stack. */
    private static final long IDLE_NANOS = 2_000_000_000L;
    /** How long a scout defers to one running worker; twice that for two, and so on up to the cap. */
    private static final long DEFER_NANOS = 10_000_000L;
    private static final int MAX_DEFER_SHIFT = 4;
    private static final long POLL_NANOS = 1_000_000L;
    /**
     * Threads that may wait at a time on a stack past its half. With 2047 frames each that adds up
     * to about 20000, around what a plain recursion reaches on the oracle's default stack.
     */
    private static final int MAX_TIGHT_WAITERS = 8;

    /** Workers in {@code helpQuiesce} with nothing to run: they do not hold the pool from being quiet. */
    private int quiescing;
    private int tightWaiters;

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

    /** Whether a task should go on another worker's fresh stack rather than on top of this one. */
    static boolean stackTight() {
        return VM.stackHalfUsed();
    }

    /** Whether a worker about to wait for {@code awaited} should run it now instead. */
    synchronized boolean takeToJoin(ForkJoinWorkerThread w, ForkJoinTask<?> awaited, boolean tight) {
        if (tight && tightWaiters < MAX_TIGHT_WAITERS) {
            return false;
        }
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

    /**
     * Queues a task invoked on a tight stack, for a worker to take. False if it should run here.
     * Whatever the factory throws is not the caller's business: its wait takes the task back.
     */
    boolean handOff(ForkJoinWorkerThread w, ForkJoinTask<?> task) {
        boolean spawn;
        synchronized (this) {
            if (parallelism == 0 || tightWaiters >= MAX_TIGHT_WAITERS) {
                return false;
            }
            if (w != null) {
                queues.pushLocal(w, task);
            } else {
                queues.addSubmission(task);
            }
            spawn = signal();
        }
        try {
            if (spawn) {
                createWorker();
            }
        } catch (Throwable noWorkerCameOfIt) {
            // Then its wait finds nobody coming.
        }
        return true;
    }

    synchronized void enterTight() {
        tightWaiters++;
    }

    synchronized void leaveTight() {
        tightWaiters--;
    }

    /**
     * The caller's stack is tight and counted as such. Waits as long as a worker may still come for
     * {@code awaited}. True if it is still queued with nobody coming: it is the caller's to run, which
     * can overflow but cannot hang. A worker stays counted as blocked until {@link #endBlock}.
     */
    boolean awaitStandIn(ForkJoinWorkerThread w, ForkJoinTask<?> awaited) {
        try {
            if (w != null) {
                beginBlock(w);
            }
        } catch (Throwable noWorkerCameOfIt) {
            // Then the loop below finds nobody coming, unless someone else asked for one.
        }
        for (;;) {
            synchronized (this) {
                if (awaited.isDone()) {
                    return false;
                }
                if (nobodyComing() || tightWaiters > MAX_TIGHT_WAITERS) {
                    return queues.removeQueued(w, awaited);
                }
            }
            LockSupport.parkNanos(this, POLL_NANOS);
        }
    }

    /** The monitor is held. */
    private boolean nobodyComing() {
        return scout == null && reserved == 0;
    }

    /**
     * Runs what it can find, then waits for the workers to have nothing to do. Blocked workers do
     * not count as doing something, nor do others waiting here ("two in helpQuiesce"), but one that
     * leaves counts again at once: the second stays until the first's task is over. On a tight stack
     * it asks for a worker first and runs tasks itself once none is coming.
     */
    void helpQuiesce(ForkJoinWorkerThread w) {
        boolean waiting = false;
        boolean asked = false;
        for (;;) {
            ForkJoinTask<?> t = null;
            boolean tight = stackTight();
            synchronized (this) {
                if (!tight || asked && nobodyComing()) {
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
                pauseAskingForWorker(w);
                asked = true;
            } else {
                LockSupport.parkNanos(this, POLL_NANOS);
            }
        }
    }

    private void pauseAskingForWorker(ForkJoinWorkerThread w) {
        try {
            beginBlock(w);
            LockSupport.parkNanos(this, POLL_NANOS);
        } catch (Throwable noWorkerCameOfIt) {
            // Then the next look finds nobody coming.
        } finally {
            endBlock(w);
        }
    }
}
