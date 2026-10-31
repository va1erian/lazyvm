package java.util.concurrent;

import java.io.Serializable;
import java.util.Collection;
import lazy.internal.Turnstile;

/**
 * A task's outcome, on its own monitor. Finishing is one short monitor section, and a thread that
 * must wait says so with {@link #waited}, so the common case never pays for a notify. Queues,
 * helping and accounting belong to {@link ForkJoinCore}.
 */
public abstract class ForkJoinTask<V> implements Future<V>, Serializable {
    private static final int PENDING = 0;
    private static final int NORMAL = 1;
    private static final int CANCELLED = 2;
    private static final int FAILED = 3;

    private volatile int status;
    private transient Throwable failure;
    /** Who settled it with {@link #failure}: any other reader gets a copy. */
    private transient Thread failedOn;
    private boolean waited;
    private short tag;

    public ForkJoinTask() {
    }

    /** Throws anything from code that declares nothing: a failure comes back as it was thrown. */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> void sneak(Throwable t) throws E {
        throw (E) t;
    }

    private void settle(int to, Throwable cause) {
        synchronized (this) {
            if (status == PENDING) {
                failure = cause;
                failedOn = cause != null ? Thread.currentThread() : null;
                status = to;
                if (waited) {
                    notifyAll();
                }
            }
        }
    }

    /** Runs this task unless it is done. Only an {@code execute(Runnable)} task's failure escapes. */
    final void run0() {
        if (status != PENDING) {
            return;
        }
        boolean finished;
        try {
            finished = exec();
        } catch (Throwable ex) {
            settle(FAILED, ex);
            if (escapes() && ForkJoinWorkerThread.current() != null) {
                ForkJoinTask.<RuntimeException>sneak(ex);
            }
            return;
        }
        if (finished) {
            settle(NORMAL, null);
        }
    }

    /** On a thread helping from outside the pool. An escaping failure reaches it as it would a worker. */
    final void runAssisting() {
        run0();
        if (escapes() && status == FAILED) {
            ForkJoinTask.<RuntimeException>sneak(failure);
        }
    }

    boolean escapes() {
        return false;
    }

    public final ForkJoinTask<V> fork() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (w != null) {
            w.core.pushLocal(w, this);
        } else {
            ForkJoinPool.shared.core.pushExternal(this);
        }
        return this;
    }

    /** As {@link #quietlyJoin}, spelled out: a frame less per level of a chain of joins goes deeper. */
    public final V join() {
        if (status == PENDING) {
            if (takeHere()) {
                run0();
            }
            awaitDone();
        }
        return report();
    }

    /** As {@link #quietlyInvoke}, spelled out for the same reason. */
    public final V invoke() {
        run0();
        quietlyJoin();
        return report();
    }

    private V report() {
        int s = status;
        if (s == NORMAL) {
            return getRawResult();
        }
        if (s == CANCELLED) {
            throw new CancellationException();
        }
        ForkJoinTask.<RuntimeException>sneak(failureHere());
        throw new AssertionError();
    }

    private Throwable failureHere() {
        return failedOn == Thread.currentThread() ? failure : FailureCopies.of(failure);
    }

    /** Whether the caller should run this task now, it being queued and the caller about to wait. */
    private boolean takeHere() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.takeToJoin(w, this) : ForkJoinPool.shared.core.takeOutside(this);
    }

    /** Waits for the outcome, with a deadline if {@code timed}. A blocked worker stops counting. */
    private void block(boolean timed, long deadline) throws InterruptedException, TimeoutException {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (w == null) {
            waitForOutcome(timed, deadline);
            return;
        }
        ForkJoinCore core = w.core;
        try {
            w.awaited = this;
            core.beginBlock(w);
            waitForOutcome(timed, deadline);
        } finally {
            w.awaited = null;
            core.endBlock(w);
        }
    }

    private synchronized void waitForOutcome(boolean timed, long deadline) throws InterruptedException, TimeoutException {
        waited = true;
        while (status == PENDING) {
            if (!timed) {
                wait();
            } else {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
        }
    }

    /** An interrupt during the wait comes back afterwards, unless this is a worker: it is dropped. */
    private void awaitDone() {
        boolean outside = ForkJoinWorkerThread.current() == null;
        boolean flagged = false;
        while (status == PENDING) {
            try {
                block(false, 0);
            } catch (InterruptedException e) {
                flagged = outside;
            } catch (TimeoutException cannotHappen) {
                throw new AssertionError(cannotHappen);
            }
        }
        if (flagged) {
            Thread.currentThread().interrupt();
        }
    }

    /** Unlike the longer forms this one leaves the second task be when the first one fails. */
    public static void invokeAll(ForkJoinTask<?> t1, ForkJoinTask<?> t2) {
        t2.fork();
        t1.invoke();
        t2.join();
    }

    public static void invokeAll(ForkJoinTask<?>... tasks) {
        Throwable first = null;
        for (int i = tasks.length - 1; i >= 0; i--) {
            ForkJoinTask<?> t = tasks[i];
            if (t == null) {
                first = first != null ? first : new NullPointerException();
            } else if (i != 0) {
                t.fork();
            } else {
                t.quietlyInvoke();
                first = first != null ? first : t.getException();
            }
        }
        for (int i = 1; i < tasks.length; i++) {
            ForkJoinTask<?> t = tasks[i];
            if (t != null) {
                if (first != null) {
                    t.cancel(false);
                } else {
                    t.quietlyJoin();
                    first = t.getException();
                }
            }
        }
        if (first != null) {
            ForkJoinTask.<RuntimeException>sneak(first);
        }
    }

    public static <T extends ForkJoinTask<?>> Collection<T> invokeAll(Collection<T> tasks) {
        invokeAll(tasks.toArray(new ForkJoinTask<?>[0]));
        return tasks;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        settle(CANCELLED, null);
        return status == CANCELLED;
    }

    public final boolean isDone() {
        return status != PENDING;
    }

    public final boolean isCancelled() {
        return status == CANCELLED;
    }

    public final boolean isCompletedAbnormally() {
        return status >= CANCELLED;
    }

    public final boolean isCompletedNormally() {
        return status == NORMAL;
    }

    public final Throwable getException() {
        int s = status;
        return s == FAILED ? failureHere() : s == CANCELLED ? new CancellationException() : null;
    }

    public void completeExceptionally(Throwable ex) {
        settle(FAILED, ex instanceof RuntimeException || ex instanceof Error ? ex : new RuntimeException(ex));
    }

    public void complete(V value) {
        try {
            setRawResult(value);
        } catch (Throwable t) {
            settle(FAILED, t);
            return;
        }
        settle(NORMAL, null);
    }

    public final void quietlyComplete() {
        settle(NORMAL, null);
    }

    /** Outside a pool {@code get} is interruptible; in a worker it is a {@code join} that wraps. */
    public final V get() throws InterruptedException, ExecutionException {
        if (ForkJoinWorkerThread.current() != null) {
            quietlyJoin();
            return reportForGet();
        }
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (status == PENDING && takeHere()) {
            run0();
        }
        try {
            while (status == PENDING) {
                block(false, 0);
            }
        } catch (TimeoutException cannotHappen) {
            throw new AssertionError(cannotHappen);
        }
        return reportForGet();
    }

    /**
     * With no time at all it just looks ("timed get in worker with no time"). Otherwise a queued task
     * runs right here for as long as it likes: the limit is for waiting ("... outlasts its limit").
     */
    public final V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        long deadline = Turnstile.deadline(nanos);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (status == PENDING && nanos <= 0) {
            throw new TimeoutException();
        }
        if (status == PENDING && takeHere()) {
            run0();
        }
        while (status == PENDING) {
            try {
                block(true, deadline);
            } catch (InterruptedException e) {
                if (w == null) {
                    throw e;
                }
            }
        }
        return reportForGet();
    }

    private V reportForGet() throws ExecutionException {
        int s = status;
        if (s == NORMAL) {
            return getRawResult();
        }
        if (s == CANCELLED) {
            throw new CancellationException();
        }
        throw new ExecutionException(failureHere());
    }

    public final void quietlyJoin() {
        if (status == PENDING) {
            if (takeHere()) {
                run0();
            }
            awaitDone();
        }
    }

    public final void quietlyInvoke() {
        run0();
        quietlyJoin();
    }

    public static void helpQuiesce() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (w != null) {
            w.core.helpQuiesce(w);
        } else {
            ForkJoinPool.shared.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    public void reinitialize() {
        synchronized (this) {
            status = PENDING;
            failure = null;
            failedOn = null;
            tag = 0;
        }
    }

    public static ForkJoinPool getPool() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.getPool() : null;
    }

    public static boolean inForkJoinPool() {
        return ForkJoinWorkerThread.current() != null;
    }

    public boolean tryUnfork() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return (w != null ? w.core : ForkJoinPool.shared.core).queues.unforkNewest(w, this);
    }

    public static int getQueuedTaskCount() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.queues.ownCount(w) : 0;
    }

    public static int getSurplusQueuedTaskCount() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.surplus(w) : 0;
    }

    public abstract V getRawResult();

    protected abstract void setRawResult(V value);

    protected abstract boolean exec();

    protected static ForkJoinTask<?> peekNextLocalTask() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.queues.nextLocal(w, false) : null;
    }

    protected static ForkJoinTask<?> pollNextLocalTask() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.queues.nextLocal(w, true) : null;
    }

    protected static ForkJoinTask<?> pollTask() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.queues.pollAny(w) : null;
    }

    public final short getForkJoinTaskTag() {
        return tag;
    }

    public final short setForkJoinTaskTag(short tag) {
        synchronized (this) {
            short old = this.tag;
            this.tag = tag;
            return old;
        }
    }

    public final boolean compareAndSetForkJoinTaskTag(short e, short tag) {
        synchronized (this) {
            if (this.tag != e) {
                return false;
            }
            this.tag = tag;
            return true;
        }
    }

    public static ForkJoinTask<?> adapt(Runnable runnable) {
        return new AdaptedTask<Object>(Executors.callable(runnable, null), AdaptedTask.RUNNABLE);
    }

    public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new AdaptedTask<T>(Executors.callable(runnable, result), AdaptedTask.RUNNABLE);
    }

    public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new AdaptedTask<T>(callable, AdaptedTask.CALLABLE);
    }
}
