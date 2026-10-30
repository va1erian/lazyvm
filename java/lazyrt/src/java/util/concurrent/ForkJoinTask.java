package java.util.concurrent;

import java.io.Serializable;
import java.util.Collection;

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
    private boolean waited;
    private short tag;

    public ForkJoinTask() {
    }

    /** Throws anything from code that declares nothing: a failure comes back as it was thrown. */
    @SuppressWarnings("unchecked")
    static <E extends Throwable> void rethrow(Throwable t) throws E {
        throw (E) t;
    }

    private void settle(int to, Throwable cause) {
        synchronized (this) {
            if (status == PENDING) {
                failure = cause;
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
                ForkJoinTask.<RuntimeException>rethrow(ex);
            }
            return;
        }
        if (finished) {
            settle(NORMAL, null);
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
            ForkJoinPool.common.core.pushExternal(this);
        }
        return this;
    }

    public final V join() {
        quietlyJoin();
        return report();
    }

    public final V invoke() {
        quietlyInvoke();
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
        ForkJoinTask.<RuntimeException>rethrow(failure);
        throw new AssertionError();
    }

    /** Lets the caller work on this task if it may; the core to tell about a block, if a worker. */
    private ForkJoinCore help() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (w != null) {
            w.core.helpJoin(w, this);
            return w.core;
        }
        ForkJoinPool.common.core.helpOutside(this);
        return null;
    }

    /** Waits for the outcome, with a deadline if {@code timed}. A blocked worker stops counting. */
    private void block(ForkJoinCore core, boolean timed, long deadline)
            throws InterruptedException, TimeoutException {
        if (status != PENDING) {
            return;
        }
        try {
            if (core != null) {
                core.beginBlock();
            }
            synchronized (this) {
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
        } finally {
            if (core != null) {
                core.endBlock();
            }
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
            ForkJoinTask.<RuntimeException>rethrow(first);
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
        return s == FAILED ? failure : s == CANCELLED ? new CancellationException() : null;
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
        if (status == PENDING) {
            try {
                block(help(), false, 0);
            } catch (TimeoutException cannotHappen) {
                throw new AssertionError(cannotHappen);
            }
        }
        return reportForGet();
    }

    public final V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        // A worker runs nothing here, as that could overrun the deadline; blocking frees another.
        if (w == null) {
            ForkJoinPool.common.core.helpOutside(this);
        }
        while (status == PENDING) {
            try {
                block(w != null ? w.core : null, true, deadline);
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
        throw new ExecutionException(failure);
    }

    /** An interrupt during the wait comes back afterwards, unless this is a worker: it is dropped. */
    public final void quietlyJoin() {
        if (status != PENDING) {
            return;
        }
        ForkJoinCore core = help();
        boolean flagged = false;
        while (status == PENDING) {
            try {
                block(core, false, 0);
            } catch (InterruptedException e) {
                flagged = core == null;
            } catch (TimeoutException cannotHappen) {
                throw new AssertionError(cannotHappen);
            }
        }
        if (flagged) {
            Thread.currentThread().interrupt();
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
            ForkJoinPool.common.awaitQuiescence(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        }
    }

    public void reinitialize() {
        synchronized (this) {
            status = PENDING;
            failure = null;
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
        return (w != null ? w.core : ForkJoinPool.common.core).unfork(w, this);
    }

    public static int getQueuedTaskCount() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.localCount(w) : 0;
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
        return w != null ? w.core.nextLocal(w, false) : null;
    }

    protected static ForkJoinTask<?> pollNextLocalTask() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.nextLocal(w, true) : null;
    }

    protected static ForkJoinTask<?> pollTask() {
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        return w != null ? w.core.pollAny(w) : null;
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
        return new Adapted<Object>(Executors.callable(runnable, null), Adapted.RUNNABLE);
    }

    public static <T> ForkJoinTask<T> adapt(Runnable runnable, T result) {
        return new Adapted<T>(Executors.callable(runnable, result), Adapted.RUNNABLE);
    }

    public static <T> ForkJoinTask<T> adapt(Callable<? extends T> callable) {
        return new Adapted<T>(callable, Adapted.CALLABLE);
    }

    /** Runs a callable or runnable as a task. A callable's checked failure goes in wrapped. */
    static final class Adapted<T> extends ForkJoinTask<T> implements RunnableFuture<T> {
        static final int CALLABLE = 0;
        static final int RUNNABLE = 1;
        /** The {@code execute(Runnable)} form, whose failure escapes to the worker. */
        static final int EXECUTED = 2;

        private final Callable<? extends T> callable;
        private final int form;
        private T result;

        Adapted(Callable<? extends T> callable, int form) {
            if (callable == null) {
                throw new NullPointerException();
            }
            this.callable = callable;
            this.form = form;
        }

        @Override
        public T getRawResult() {
            return result;
        }

        @Override
        protected void setRawResult(T value) {
            result = value;
        }

        @Override
        protected boolean exec() {
            try {
                result = callable.call();
                return true;
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                if (form == CALLABLE) {
                    throw new RuntimeException(e);
                }
                ForkJoinTask.<RuntimeException>rethrow(e);
                return false;
            }
        }

        @Override
        public void run() {
            invoke();
        }

        @Override
        boolean escapes() {
            return form == EXECUTED;
        }
    }
}
