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

    /** Guards every field below. Nothing that can run a program's code is called under it. */
    private static final Object LOCK = new Object();
    private static final Map<Long, LoopFuture<?>> OPERATIONS = new HashMap<>();
    private static final ArrayDeque<Runnable> TASKS = new ArrayDeque<>();
    private static long nextId = 1;
    private static int pending;
    private static boolean dispatching, keeping;

    private Reactor() {}

    /** Runs {@code task} on the loop's thread. */
    public static void execute(Runnable task) {
        if (task == null) throw new NullPointerException("task");
        synchronized (LOCK) {
            count();
            TASKS.add(task);
        }
        wake0();
    }

    /** A stage that completes after {@code nanos} (not negative). */
    public static CompletionStage<Void> delay(long nanos) {
        LoopFuture<Void> stage = begin();
        try {
            timer0(stage.id(), nanos);
        } catch (IOException e) {
            failLater(stage, e);
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
        synchronized (LOCK) {
            count();
            LoopFuture<T> stage = new LoopFuture<>(nextId++);
            OPERATIONS.put(stage.id(), stage);
            return stage;
        }
    }

    /** One more pending thing, with both threads up before the caller's call returns. */
    private static void count() {
        if (!dispatching) {
            start("lazy-loop", true, Reactor::dispatch);
            dispatching = true;
        }
        if (!keeping) {
            start("lazy-loop-keeper", false, Reactor::keep);
            keeping = true;
        }
        pending++;
    }

    private static void start(String name, boolean daemon, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(daemon);
        thread.start();
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

    /** The dispatcher: the loop's thread, for the life of the VM. */
    private static void dispatch() {
        long[] done = new long[BATCH];
        for (;;) {
            try {
                int n = take0(done);
                for (int i = 0; i < n; i++) deliver(done[i]);
                runTasks();
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
