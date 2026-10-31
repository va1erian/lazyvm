package java.util.concurrent;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A thread pool over a {@link DelayQueue} of scheduled tasks, so a worker only gets what is due. */
public class ScheduledThreadPoolExecutor extends ThreadPoolExecutor implements ScheduledExecutorService {
    private final AtomicLong submissions = new AtomicLong();
    private volatile boolean keepPeriodic;
    private volatile boolean keepDelayed = true;
    private volatile boolean removeOnCancel;

    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, TimeUnit.NANOSECONDS, newQueue());
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, TimeUnit.NANOSECONDS, newQueue(), threadFactory);
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, TimeUnit.NANOSECONDS, newQueue(), handler);
    }

    public ScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory,
            RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, TimeUnit.NANOSECONDS, newQueue(), threadFactory, handler);
    }

    /** Only scheduled tasks go in, and they are all both: the types just cannot say so. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockingQueue<Runnable> newQueue() {
        return (BlockingQueue) new DelayQueue();
    }

    private final class ScheduledTask<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
        private final long order = submissions.getAndIncrement();
        private final long period;
        private final boolean fixedRate;
        private volatile long time;
        RunnableScheduledFuture<V> outer = this;

        ScheduledTask(Callable<V> c, long time) {
            super(c);
            this.time = time;
            this.period = 0;
            this.fixedRate = false;
        }

        ScheduledTask(Runnable r, V result, long time, long period, boolean fixedRate) {
            super(r, result);
            this.time = time;
            this.period = period;
            this.fixedRate = fixedRate;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(time - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            if (other instanceof ScheduledTask) {
                ScheduledTask<?> o = (ScheduledTask<?>) other;
                long diff = time - o.time;
                return diff < 0 ? -1 : diff > 0 ? 1 : order < o.order ? -1 : 1;
            }
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return diff < 0 ? -1 : diff > 0 ? 1 : 0;
        }

        @Override
        public boolean isPeriodic() {
            return period != 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && removeOnCancel) {
                remove(this);
            }
            return cancelled;
        }

        @Override
        public void run() {
            boolean periodic = isPeriodic();
            if (!mayRun(periodic ? keepPeriodic : keepDelayed)) {
                cancel(false);
            } else if (!periodic) {
                super.run();
            } else if (super.runAndReset()) {
                time = fixedRate ? time + period : dueAfter(period);
                requeue(outer);
            }
        }
    }

    /**
     * The clock time {@code nanos} from now. Times are compared by difference, so a huge delay
     * stays one that difference can express against the most overdue task. The clock is read
     * before the head's delay, so time passing in between only makes the margin larger.
     */
    private long dueAfter(long nanos) {
        long now = System.nanoTime();
        long delay = Math.max(nanos, 0);
        if (delay > Long.MAX_VALUE >> 1) {
            Delayed head = (Delayed) (Object) pool.queue.peek();
            long overdue = head == null ? 0 : -head.getDelay(TimeUnit.NANOSECONDS);
            if (overdue > 0 && delay + overdue < 0) {
                delay = Long.MAX_VALUE - overdue;
            }
        }
        return now + delay;
    }

    private boolean keeps(RunnableScheduledFuture<?> t) {
        return t.isPeriodic() ? keepPeriodic : keepDelayed;
    }

    private void enqueue(RunnableScheduledFuture<?> t) {
        if (isShutdown()) {
            reject(t);
            return;
        }
        pool.queue.add(t);
        if (!mayRun(keeps(t)) && remove(t)) {
            t.cancel(false);
        } else {
            ensureWorker();
        }
    }

    /** A task that ends its run after {@code shutdown} just stops, neither done nor cancelled. */
    private void requeue(RunnableScheduledFuture<?> t) {
        if (!mayRun(keepPeriodic)) {
            return;
        }
        pool.queue.add(t);
        if (!mayRun(keepPeriodic) && remove(t)) {
            t.cancel(false);
        } else {
            ensureWorker();
        }
    }

    @Override
    void onShutdown() {
        BlockingQueue<Runnable> q = pool.queue;
        for (Object e : q.toArray()) {
            if (e instanceof RunnableScheduledFuture) {
                RunnableScheduledFuture<?> t = (RunnableScheduledFuture<?>) e;
                if ((!keeps(t) || t.isCancelled()) && q.remove(t)) {
                    t.cancel(false);
                }
            }
        }
    }

    protected <V> RunnableScheduledFuture<V> decorateTask(Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    protected <V> RunnableScheduledFuture<V> decorateTask(Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        RunnableScheduledFuture<Void> t = decorateTask(command,
                new ScheduledTask<Void>(command, null, dueAfter(unit.toNanos(delay)), 0, false));
        enqueue(t);
        return t;
    }

    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null || unit == null) {
            throw new NullPointerException();
        }
        RunnableScheduledFuture<V> t = decorateTask(callable,
                new ScheduledTask<V>(callable, dueAfter(unit.toNanos(delay))));
        enqueue(t);
        return t;
    }

    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return schedulePeriodic(command, initialDelay, period, unit, true);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return schedulePeriodic(command, initialDelay, delay, unit, false);
    }

    private ScheduledFuture<?> schedulePeriodic(Runnable command, long initialDelay, long period, TimeUnit unit,
            boolean fixedRate) {
        if (command == null || unit == null) {
            throw new NullPointerException();
        }
        if (period <= 0) {
            throw new IllegalArgumentException();
        }
        ScheduledTask<Void> task = new ScheduledTask<Void>(command, null, dueAfter(unit.toNanos(initialDelay)),
                unit.toNanos(period), fixedRate);
        RunnableScheduledFuture<Void> t = decorateTask(command, task);
        task.outer = t;
        enqueue(t);
        return t;
    }

    public void execute(Runnable command) {
        schedule(command, 0, TimeUnit.NANOSECONDS);
    }

    public Future<?> submit(Runnable task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, TimeUnit.NANOSECONDS);
    }

    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, TimeUnit.NANOSECONDS);
    }

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        keepPeriodic = value;
        settlePolicy(value);
    }

    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return keepPeriodic;
    }

    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        keepDelayed = value;
        settlePolicy(value);
    }

    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return keepDelayed;
    }

    /** A policy turned off after {@code shutdown} applies to what is still queued. */
    private void settlePolicy(boolean value) {
        if (!value && isShutdown()) {
            onShutdown();
            pool.endIfDone();
        }
    }

    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    public void shutdown() {
        super.shutdown();
    }

    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }
}
