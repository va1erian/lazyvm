package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** The public face; {@link ForkJoinCore} holds the queues, the workers and the run state. */
public class ForkJoinPool extends AbstractExecutorService {
    public static interface ForkJoinWorkerThreadFactory {
        ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    public static interface ManagedBlocker {
        boolean block() throws InterruptedException;

        boolean isReleasable();
    }

    private static final class PlainWorkers implements ForkJoinWorkerThreadFactory {
        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return new ForkJoinWorkerThread(pool);
        }
    }

    public static final ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory = new PlainWorkers();
    private static int poolNumbers;
    static final ForkJoinPool common = makeCommon();

    final ForkJoinCore core;
    private final int parallelism;
    private final ForkJoinWorkerThreadFactory factory;
    private final Thread.UncaughtExceptionHandler handler;
    private final boolean asyncMode;

    public ForkJoinPool() {
        this(Runtime.getRuntime().availableProcessors());
    }

    public ForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    public ForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory,
            Thread.UncaughtExceptionHandler handler, boolean asyncMode) {
        this(checked(parallelism), factory, handler, asyncMode, "ForkJoinPool-" + nextPoolNumber() + "-worker-");
    }

    private ForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory,
            Thread.UncaughtExceptionHandler handler, boolean asyncMode, String workerPrefix) {
        if (factory == null) {
            throw new NullPointerException();
        }
        // A common pool set to 0 has no workers but reports 1.
        this.parallelism = Math.max(parallelism, 1);
        this.factory = factory;
        this.handler = handler;
        this.asyncMode = asyncMode;
        this.core = new ForkJoinCore(this, parallelism, asyncMode, workerPrefix);
    }

    private static int checked(int parallelism) {
        if (parallelism <= 0 || parallelism > ForkJoinCore.MAX_WORKERS) {
            throw new IllegalArgumentException();
        }
        return parallelism;
    }

    private static synchronized int nextPoolNumber() {
        return ++poolNumbers;
    }

    private static ForkJoinPool makeCommon() {
        int parallelism = -1;
        ForkJoinWorkerThreadFactory factory = defaultForkJoinWorkerThreadFactory;
        Thread.UncaughtExceptionHandler handler = null;
        try {
            String p = System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism");
            String f = System.getProperty("java.util.concurrent.ForkJoinPool.common.threadFactory");
            String h = System.getProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            if (p != null) {
                parallelism = Integer.parseInt(p);
            }
            if (f != null) {
                factory = (ForkJoinWorkerThreadFactory) Class.forName(f).newInstance();
            }
            if (h != null) {
                handler = (Thread.UncaughtExceptionHandler) Class.forName(h).newInstance();
            }
        } catch (Exception badSettingsAreIgnored) {
            handler = null;
        }
        if (parallelism < 0) {
            parallelism = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
        }
        parallelism = Math.min(parallelism, ForkJoinCore.MAX_WORKERS);
        return new ForkJoinPool(parallelism, factory, handler, false, "ForkJoinPool.commonPool-worker-");
    }

    public static ForkJoinPool commonPool() {
        return common;
    }

    public <T> T invoke(ForkJoinTask<T> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        core.pushExternal(task);
        return task.join();
    }

    public void execute(ForkJoinTask<?> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        core.pushExternal(task);
    }

    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException();
        }
        core.pushExternal(task instanceof ForkJoinTask<?> ? (ForkJoinTask<?>) task
                : new ForkJoinTask.Adapted<Void>(Executors.<Void>callable(task, null), ForkJoinTask.Adapted.EXECUTED));
    }

    public <T> ForkJoinTask<T> submit(ForkJoinTask<T> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        core.pushExternal(task);
        return task;
    }

    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        return submit(ForkJoinTask.<T>adapt(task));
    }

    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        return submit(ForkJoinTask.adapt(task, result));
    }

    public ForkJoinTask<?> submit(Runnable task) {
        ForkJoinTask<?> t = task instanceof ForkJoinTask<?> ? (ForkJoinTask<?>) task : ForkJoinTask.adapt(task);
        core.pushExternal(t);
        return t;
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> c : tasks) {
                futures.add(submit(c));
            }
            for (Future<T> f : futures) {
                ((ForkJoinTask<T>) f).quietlyJoin();
            }
            finished = true;
            return futures;
        } finally {
            if (!finished) {
                for (Future<T> f : futures) {
                    f.cancel(false);
                }
            }
        }
    }

    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return handler;
    }

    public int getParallelism() {
        return parallelism;
    }

    public static int getCommonPoolParallelism() {
        return common.parallelism;
    }

    public int getPoolSize() {
        return core.poolSize();
    }

    public boolean getAsyncMode() {
        return asyncMode;
    }

    public int getRunningThreadCount() {
        return core.running();
    }

    public int getActiveThreadCount() {
        return core.active();
    }

    public boolean isQuiescent() {
        return core.isQuiescent();
    }

    public long getStealCount() {
        return core.steals();
    }

    public long getQueuedTaskCount() {
        return core.queuedTasks();
    }

    public int getQueuedSubmissionCount() {
        return core.queuedSubmissions();
    }

    public boolean hasQueuedSubmissions() {
        return core.queuedSubmissions() > 0;
    }

    protected ForkJoinTask<?> pollSubmission() {
        return core.pollSubmission();
    }

    protected int drainTasksTo(Collection<? super ForkJoinTask<?>> c) {
        int n = 0;
        for (ForkJoinTask<?> t = core.pollForeign(); t != null; t = core.pollForeign()) {
            c.add(t);
            n++;
        }
        return n;
    }

    public String toString() {
        return super.toString() + core.describe();
    }

    public void shutdown() {
        if (this != common) {
            core.shutdown();
        }
    }

    public List<Runnable> shutdownNow() {
        if (this != common) {
            core.stop();
        }
        return Collections.emptyList();
    }

    public boolean isTerminated() {
        return core.terminated;
    }

    public boolean isTerminating() {
        return core.terminating && !core.terminated;
    }

    public boolean isShutdown() {
        return core.shutdown;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (this == common) {
            awaitQuiescence(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            return false;
        }
        return core.awaitTerminated(deadline);
    }

    public boolean awaitQuiescence(long timeout, TimeUnit unit) {
        long left = unit.toNanos(timeout);
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        if (w != null && w.getPool() == this) {
            core.helpQuiesce(w);
            return true;
        }
        long start = System.nanoTime();
        while (!core.isQuiescent()) {
            if (System.nanoTime() - start >= left) {
                return false;
            }
            Thread.yield();
        }
        return true;
    }

    public static void managedBlock(ManagedBlocker blocker) throws InterruptedException {
        if (blocker == null) {
            throw new NullPointerException();
        }
        ForkJoinWorkerThread w = ForkJoinWorkerThread.current();
        try {
            if (w != null) {
                w.core.beginBlock();
            }
            while (!blocker.isReleasable() && !blocker.block()) {
                continue;
            }
        } finally {
            if (w != null) {
                w.core.endBlock();
            }
        }
    }

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new ForkJoinTask.Adapted<T>(Executors.callable(runnable, value), ForkJoinTask.Adapted.RUNNABLE);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new ForkJoinTask.Adapted<T>(callable, ForkJoinTask.Adapted.CALLABLE);
    }
}
