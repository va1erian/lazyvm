package java.util.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Executors {
    private Executors() {
    }

    public static ExecutorService newFixedThreadPool(int nThreads) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
    }

    public static ExecutorService newWorkStealingPool(int parallelism) {
        return new ForkJoinPool(parallelism, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
    }

    public static ExecutorService newWorkStealingPool() {
        return newWorkStealingPool(Runtime.getRuntime().availableProcessors());
    }

    public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), threadFactory);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return new HiddenPool(newFixedThreadPool(1));
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        return new HiddenPool(newFixedThreadPool(1, threadFactory));
    }

    public static ExecutorService newCachedThreadPool() {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                threadFactory);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return new HiddenScheduledPool(new ScheduledThreadPoolExecutor(1));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        return new HiddenScheduledPool(new ScheduledThreadPoolExecutor(1, threadFactory));
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return new ScheduledThreadPoolExecutor(corePoolSize);
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory threadFactory) {
        return new ScheduledThreadPoolExecutor(corePoolSize, threadFactory);
    }

    public static ExecutorService unconfigurableExecutorService(ExecutorService executor) {
        return new HiddenPool(executor);
    }

    public static ScheduledExecutorService unconfigurableScheduledExecutorService(ScheduledExecutorService executor) {
        return new HiddenScheduledPool(executor);
    }

    public static ThreadFactory defaultThreadFactory() {
        return new NumberedThreads();
    }

    public static ThreadFactory privilegedThreadFactory() {
        return new NumberedThreads();
    }

    public static <T> Callable<T> callable(Runnable task, T result) {
        if (task == null) {
            throw new NullPointerException();
        }
        return () -> {
            task.run();
            return result;
        };
    }

    public static Callable<Object> callable(Runnable task) {
        return callable(task, null);
    }

    public static <T> Callable<T> privilegedCallable(Callable<T> callable) {
        // There is no security manager, so there is no access context to carry over.
        if (callable == null) {
            throw new NullPointerException();
        }
        return callable;
    }

    public static <T> Callable<T> privilegedCallableUsingCurrentClassLoader(Callable<T> callable) {
        return privilegedCallable(callable);
    }

    private static final class NumberedThreads implements ThreadFactory {
        private static final AtomicInteger POOLS = new AtomicInteger();

        private final ThreadGroup group = Thread.currentThread().getThreadGroup();
        private final String prefix = "pool-" + POOLS.incrementAndGet() + "-thread-";
        private final AtomicInteger threads = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(group, r, prefix + threads.incrementAndGet());
            t.setDaemon(false);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        }
    }

    /** Shows only the service's interface, so its configuration stays out of reach. */
    private static class HiddenPool implements ExecutorService {
        private final ExecutorService pool;

        HiddenPool(ExecutorService pool) {
            if (pool == null) {
                throw new NullPointerException();
            }
            this.pool = pool;
        }

        @Override
        public void execute(Runnable command) {
            pool.execute(command);
        }

        @Override
        public void shutdown() {
            pool.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return pool.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return pool.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return pool.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return pool.awaitTermination(timeout, unit);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return pool.submit(task);
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
            return pool.submit(task, result);
        }

        @Override
        public Future<?> submit(Runnable task) {
            return pool.submit(task);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
            return pool.invokeAll(tasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException {
            return pool.invokeAll(tasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                throws InterruptedException, ExecutionException {
            return pool.invokeAny(tasks);
        }

        @Override
        public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                throws InterruptedException, ExecutionException, TimeoutException {
            return pool.invokeAny(tasks, timeout, unit);
        }
    }

    private static final class HiddenScheduledPool extends HiddenPool implements ScheduledExecutorService {
        private final ScheduledExecutorService pool;

        HiddenScheduledPool(ScheduledExecutorService pool) {
            super(pool);
            this.pool = pool;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return pool.schedule(command, delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return pool.schedule(callable, delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period,
                TimeUnit unit) {
            return pool.scheduleAtFixedRate(command, initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                TimeUnit unit) {
            return pool.scheduleWithFixedDelay(command, initialDelay, delay, unit);
        }
    }
}
