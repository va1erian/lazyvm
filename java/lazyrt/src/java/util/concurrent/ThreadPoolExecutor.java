package java.util.concurrent;

import java.util.List;

/** The public face of a pool over the caller's queue; {@link PoolWorkers} holds the machinery. */
public class ThreadPoolExecutor extends AbstractExecutorService {
    private static final RejectedExecutionHandler DEFAULT_HANDLER = new AbortPolicy();

    final PoolWorkers pool;
    private volatile RejectedExecutionHandler handler;

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(),
                DEFAULT_HANDLER);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, DEFAULT_HANDLER);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Executors.defaultThreadFactory(),
                handler);
    }

    public ThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException();
        }
        if (workQueue == null || threadFactory == null || handler == null) {
            throw new NullPointerException();
        }
        this.pool = new PoolWorkers(this, workQueue);
        pool.core = corePoolSize;
        pool.max = maximumPoolSize;
        pool.keepAliveNanos = unit.toNanos(keepAliveTime);
        pool.factory = threadFactory;
        this.handler = handler;
    }

    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        if (pool.startWorker(command, true)) {
            return;
        }
        if (pool.state == PoolWorkers.RUNNING && pool.queue.offer(command)) {
            // A shutdown in between may already have counted the queue as empty.
            if (pool.state != PoolWorkers.RUNNING && pool.queue.remove(command)) {
                pool.endIfDone();
                reject(command);
            } else if (pool.size() == 0) {
                pool.startWorker(null, false);
            }
        } else if (!pool.startWorker(command, false)) {
            reject(command);
        }
    }

    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    public void shutdown() {
        pool.markShutdown();
        onShutdown();
        pool.endIfDone();
    }

    /** What a subclass must do to its queue at {@code shutdown}. */
    void onShutdown() {
    }

    public List<Runnable> shutdownNow() {
        return pool.stop();
    }

    public boolean isShutdown() {
        return pool.state != PoolWorkers.RUNNING;
    }

    public boolean isTerminating() {
        int s = pool.state;
        return s != PoolWorkers.RUNNING && s != PoolWorkers.TERMINATED;
    }

    public boolean isTerminated() {
        return pool.state == PoolWorkers.TERMINATED;
    }

    /** Whether a task may still start in this state, given the subclass's after-shutdown policy. */
    final boolean mayRun(boolean afterShutdown) {
        int s = pool.state;
        return s == PoolWorkers.RUNNING || s == PoolWorkers.SHUTDOWN && afterShutdown;
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return pool.awaitTermination(timeout, unit);
    }

    protected void finalize() {
        shutdown();
    }

    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null) {
            throw new NullPointerException();
        }
        pool.factory = threadFactory;
    }

    public ThreadFactory getThreadFactory() {
        return pool.factory;
    }

    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null) {
            throw new NullPointerException();
        }
        this.handler = handler;
    }

    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0) {
            throw new IllegalArgumentException();
        }
        pool.setCore(corePoolSize);
    }

    public int getCorePoolSize() {
        return pool.core;
    }

    public boolean prestartCoreThread() {
        return pool.startWorker(null, true);
    }

    public int prestartAllCoreThreads() {
        int made = 0;
        while (pool.startWorker(null, true)) {
            made++;
        }
        return made;
    }

    public boolean allowsCoreThreadTimeOut() {
        return pool.coreTimesOut;
    }

    public void allowCoreThreadTimeOut(boolean value) {
        if (value && pool.keepAliveNanos <= 0) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        pool.setCoreTimesOut(value);
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < pool.core) {
            throw new IllegalArgumentException();
        }
        pool.setMax(maximumPoolSize);
    }

    public int getMaximumPoolSize() {
        return pool.max;
    }

    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0) {
            throw new IllegalArgumentException();
        }
        if (time == 0 && pool.coreTimesOut) {
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        }
        pool.setKeepAlive(unit.toNanos(time));
    }

    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(pool.keepAliveNanos, TimeUnit.NANOSECONDS);
    }

    public BlockingQueue<Runnable> getQueue() {
        return pool.queue;
    }

    public boolean remove(Runnable task) {
        boolean removed = pool.queue.remove(task);
        pool.endIfDone();
        return removed;
    }

    public void purge() {
        for (Object r : pool.queue.toArray()) {
            if (r instanceof Future<?> && ((Future<?>) r).isCancelled()) {
                pool.queue.remove(r);
            }
        }
        pool.endIfDone();
    }

    /** Starts a worker for a queue that just got a task, if the pool has room or nobody at all. */
    final void ensureWorker() {
        if (!pool.startWorker(null, true) && pool.size() == 0) {
            pool.startWorker(null, false);
        }
    }

    public int getPoolSize() {
        return pool.size();
    }

    public int getActiveCount() {
        return pool.busy();
    }

    public int getLargestPoolSize() {
        return pool.largest();
    }

    public long getTaskCount() {
        return pool.completed() + pool.busy() + pool.queue.size();
    }

    public long getCompletedTaskCount() {
        return pool.completed();
    }

    public String toString() {
        int s = pool.state;
        String word = s == PoolWorkers.RUNNING ? "Running" : s == PoolWorkers.TERMINATED ? "Terminated" : "Shutting down";
        return super.toString() + "[" + word + ", pool size = " + pool.size() + ", active threads = " + pool.busy()
                + ", queued tasks = " + pool.queue.size() + ", completed tasks = " + pool.completed() + "]";
    }

    protected void beforeExecute(Thread t, Runnable r) {
    }

    protected void afterExecute(Runnable r, Throwable t) {
    }

    protected void terminated() {
    }

    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        public CallerRunsPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    public static class AbortPolicy implements RejectedExecutionHandler {
        public AbortPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() + " rejected from " + e.toString());
        }
    }

    public static class DiscardPolicy implements RejectedExecutionHandler {
        public DiscardPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        public DiscardOldestPolicy() {
        }

        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
