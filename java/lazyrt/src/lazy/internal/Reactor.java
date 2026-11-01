package lazy.internal;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * The Java side of the event loop (docs/async.md, "Inside"): the table of operations, the count
 * of pending work, the dispatcher thread that delivers completions and runs tasks, and the keeper
 * thread that holds the VM open while anything is pending. The natives reach the reactor thread
 * in {@code platform::reactor}.
 */
public final class Reactor {
    private static final int BATCH = 256;

    /**
     * Guards the mutable fields below. Nothing that can run a program's code happens under it,
     * which is why threads are made outside it: a new thread runs the program's
     * InheritableThreadLocal.childValue.
     */
    private static final Object LOCK = new Object();
    private static final Map<Long, LoopFuture<?>> OPERATIONS = new HashMap<>();
    private static final ArrayDeque<Runnable> TASKS = new ArrayDeque<>();
    /** Made here, so that a lack of memory reaches the first caller and not a dead dispatcher. */
    private static final long[] DONE = new long[BATCH];
    private static final Thread DISPATCHER = thread("lazy-loop", true, Reactor::dispatch);
    private static long nextId = 1;
    private static int pending;
    private static boolean dispatching, keeping;

    private Reactor() {}

    /** Runs {@code task} on the loop's thread. */
    public static void execute(Runnable task) {
        if (task == null) throw new NullPointerException("task");
        enter(() -> TASKS.add(task));
        wake0();
    }

    /** A stage that completes after {@code nanos} (not negative). */
    public static CompletionStage<Void> delay(long nanos) {
        if (nanos < 0) throw new IllegalArgumentException("negative delay");
        LoopFuture<Void> stage = begin();
        try {
            try {
                timer0(stage.id(), nanos);
            } catch (IOException e) {
                failLater(stage, e);
            }
        } catch (RuntimeException | Error t) {
            if (withdraw(stage.id())) finished();
            throw t;
        }
        return stage;
    }

    /** Operations and tasks not yet finished, handlers included. For tests. */
    public static int pending() {
        synchronized (LOCK) {
            return pending;
        }
    }

    /** How many times the reactor thread has woken. For tests: an idle loop must not. */
    public static long wakes() {
        return wakes0();
    }

    private static <T> LoopFuture<T> begin() {
        long id;
        synchronized (LOCK) {
            id = nextId++;
        }
        LoopFuture<T> stage = new LoopFuture<>(id);
        enter(() -> OPERATIONS.put(id, stage));
        return stage;
    }

    /**
     * Records {@code work} and counts it, with both threads up before the caller's call returns.
     * It counts last, so work that fails for lack of memory leaves nothing counted, and a keeper
     * it started finds the count at 0 and ends.
     */
    private static void enter(Runnable work) {
        Thread keeper = null;
        for (;;) {
            synchronized (LOCK) {
                if (threadsUp(keeper)) {
                    work.run();
                    pending++;
                    return;
                }
            }
            keeper = thread("lazy-loop-keeper", false, Reactor::keep);
        }
    }

    /** Under the lock: false if a keeper is needed and the caller has yet to make one. */
    private static boolean threadsUp(Thread keeper) {
        if (!keeping) {
            if (keeper == null) return false;
            keeper.start();
            keeping = true;
        }
        if (!dispatching) {
            DISPATCHER.start();
            dispatching = true;
        }
        return true;
    }

    /** In the root group, so what happens to the loop does not depend on who used it first. */
    private static Thread thread(String name, boolean daemon, Runnable body) {
        ThreadGroup group = Thread.currentThread().getThreadGroup();
        while (group.getParent() != null) group = group.getParent();
        Thread thread = new Thread(group, body, name);
        thread.setDaemon(daemon);
        return thread;
    }

    /** Takes {@code id} out of the table; true if the caller now settles it. */
    static boolean withdraw(long id) {
        synchronized (LOCK) {
            if (OPERATIONS.remove(id) == null) return false;
        }
        cancel0(id);
        return true;
    }

    /** One pending thing is over, its handlers included. */
    static void finished() {
        synchronized (LOCK) {
            if (--pending == 0) LOCK.notifyAll();
        }
    }

    private static LoopFuture<?> claim(long id) {
        synchronized (LOCK) {
            return OPERATIONS.remove(id);
        }
    }

    /** A stage is complete only on the loop, so a failure in the call waits for it too. */
    private static void failLater(LoopFuture<?> stage, IOException cause) {
        execute(() -> {
            if (claim(stage.id()) == null) return;
            try {
                stage.fail(cause);
            } finally {
                finished();
            }
        });
    }

    /** The keeper: a non-daemon thread alive only while something is pending. */
    private static void keep() {
        synchronized (LOCK) {
            while (pending > 0) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    // Nothing asks a keeper to stop but the count.
                }
            }
            keeping = false;
        }
    }

    /**
     * The dispatcher: the loop's thread, for the life of the VM. The ids taken are already out of
     * the inbox, so whatever is thrown (claiming one boxes it, and the heap may be full), it goes
     * on from the id it was at.
     */
    private static void dispatch() {
        int next = 0, taken = 0;
        for (;;) {
            try {
                while (next < taken) {
                    deliver(DONE[next]);
                    next++;
                }
                runTasks();
                taken = take0(DONE);
                next = 0;
            } catch (Throwable t) {
                report(t);
            }
        }
    }

    private static void deliver(long id) {
        LoopFuture<?> stage = claim(id);
        if (stage == null) return;
        try {
            stage.settle(null);
        } catch (Throwable t) {
            report(t);
        } finally {
            finished();
        }
    }

    /** The tasks queued so far: one that keeps queueing tasks must not starve completions. */
    private static void runTasks() {
        int n;
        synchronized (LOCK) {
            n = TASKS.size();
        }
        for (; n > 0; n--) {
            Runnable task;
            synchronized (LOCK) {
                task = TASKS.poll();
            }
            try {
                task.run();
            } catch (Throwable t) {
                report(t);
            } finally {
                finished();
            }
        }
    }

    private static void report(Throwable t) {
        Thread me = Thread.currentThread();
        try {
            me.getUncaughtExceptionHandler().uncaughtException(me, t);
        } catch (Throwable ignored) {
            // A failing handler must not end the loop; a thread's end ignores it too.
        }
    }

    private static native void timer0(long id, long nanos) throws IOException;
    private static native void cancel0(long id);
    /** Blocks until completions arrive or {@link #wake0} is called; returns how many ids it wrote. */
    private static native int take0(long[] ids);
    private static native void wake0();
    private static native long wakes0();
}
