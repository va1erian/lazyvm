package java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import lazy.internal.Turnstile;

/** Every entry point makes its future with {@code newTaskFor} and hands it to {@code execute}. */
public abstract class AbstractExecutorService implements ExecutorService {
    public AbstractExecutorService() {
    }

    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<T>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<T>(callable);
    }

    public Future<?> submit(Runnable task) {
        return submit(task, null);
    }

    public <T> Future<T> submit(Runnable task, T result) {
        if (task == null) {
            throw new NullPointerException();
        }
        RunnableFuture<T> f = newTaskFor(task, result);
        execute(f);
        return f;
    }

    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) {
            throw new NullPointerException();
        }
        RunnableFuture<T> f = newTaskFor(task);
        execute(f);
        return f;
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        try {
            return race(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            throw new AssertionError(cannotHappen);
        }
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return race(tasks, true, unit.toNanos(timeout));
    }

    /**
     * Starts tasks one at a time, a further one only while nothing has finished, so a quick first
     * task means the others never start. Whoever is still running at the end is cancelled.
     */
    private <T> T race(Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException();
        }
        long deadline = Turnstile.deadline(nanos);
        ExecutorCompletionService<T> finished = new ExecutorCompletionService<T>(this);
        List<Future<T>> started = new ArrayList<Future<T>>(tasks.size());
        Iterator<? extends Callable<T>> next = tasks.iterator();
        ExecutionException lastFailure = null;
        int running = 0;
        try {
            while (running > 0 || next.hasNext()) {
                Future<T> f = finished.poll();
                if (f == null) {
                    if (next.hasNext()) {
                        started.add(finished.submit(next.next()));
                        running++;
                        continue;
                    }
                    f = timed ? finished.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) : finished.take();
                    if (f == null) {
                        throw new TimeoutException();
                    }
                }
                running--;
                try {
                    return f.get();
                } catch (ExecutionException e) {
                    lastFailure = e;
                } catch (RuntimeException e) {
                    lastFailure = new ExecutionException(e);
                }
            }
            throw lastFailure;
        } finally {
            cancelAll(started);
        }
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> c : tasks) {
                RunnableFuture<T> f = newTaskFor(c);
                futures.add(f);
                execute(f);
            }
            for (Future<T> f : futures) {
                awaitQuietly(f);
            }
            finished = true;
            return futures;
        } finally {
            if (!finished) {
                cancelAll(futures);
            }
        }
    }

    private static void awaitQuietly(Future<?> f) throws InterruptedException {
        if (!f.isDone()) {
            try {
                f.get();
            } catch (CancellationException | ExecutionException outcomeStaysInTheFuture) {
                return;
            }
        }
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        // Not clamped: at the lowest timeout the sum wraps, and the wait has no limit, as on the oracle.
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        List<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean finished = false;
        try {
            for (Callable<T> c : tasks) {
                futures.add(newTaskFor(c));
            }
            finished = executeUntil(futures, deadline) && awaitUntil(futures, deadline);
            return futures;
        } finally {
            if (!finished) {
                cancelAll(futures);
            }
        }
    }

    private <T> boolean executeUntil(List<Future<T>> futures, long deadline) {
        for (Future<T> f : futures) {
            execute((Runnable) f);
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
        }
        return true;
    }

    private static <T> boolean awaitUntil(List<Future<T>> futures, long deadline) throws InterruptedException {
        for (Future<T> f : futures) {
            if (!f.isDone()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    f.get(left, TimeUnit.NANOSECONDS);
                } catch (CancellationException | ExecutionException outcomeStaysInTheFuture) {
                    continue;
                } catch (TimeoutException e) {
                    return false;
                }
            }
        }
        return true;
    }

    private static <T> void cancelAll(List<Future<T>> futures) {
        for (Future<T> f : futures) {
            f.cancel(true);
        }
    }
}
