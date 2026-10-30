package java.util.concurrent;

/**
 * A computation with one outcome, on a private monitor: every change of state and of the runner
 * is under it, so an interrupt from {@code cancel(true)} can only hit a thread that is still
 * inside {@code run}, and never one that has moved on.
 */
public class FutureTask<V> implements RunnableFuture<V> {
    private static final int PENDING = 0;
    private static final int SUCCEEDED = 1;
    private static final int FAILED = 2;
    private static final int CANCELLED = 3;

    private final Object mu = new Object();
    private Callable<V> callable;
    private volatile int state;
    private Object settled;
    private Thread runner;

    public FutureTask(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
    }

    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
    }

    public boolean isCancelled() {
        return state == CANCELLED;
    }

    public boolean isDone() {
        return state != PENDING;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        synchronized (mu) {
            if (state != PENDING) {
                return false;
            }
            if (mayInterruptIfRunning && runner != null) {
                runner.interrupt();
            }
            settle(CANCELLED, null);
        }
        done();
        return true;
    }

    public V get() throws InterruptedException, ExecutionException {
        if (state == PENDING) {
            synchronized (mu) {
                while (state == PENDING) {
                    mu.wait();
                }
            }
        }
        return report();
    }

    public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (state == PENDING) {
            // A pending task looks at the flag even with no time left (docs/design/concurrent.md G5).
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            synchronized (mu) {
                while (state == PENDING) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new TimeoutException();
                    }
                    TimeUnit.NANOSECONDS.timedWait(mu, left);
                }
            }
        }
        return report();
    }

    @SuppressWarnings("unchecked")
    private V report() throws ExecutionException {
        int s = state;
        if (s == SUCCEEDED) {
            return (V) settled;
        }
        if (s == CANCELLED) {
            throw new CancellationException();
        }
        throw new ExecutionException((Throwable) settled);
    }

    protected void done() {
    }

    protected void set(V v) {
        if (complete(SUCCEEDED, v)) {
            done();
        }
    }

    protected void setException(Throwable t) {
        if (complete(FAILED, t)) {
            done();
        }
    }

    private boolean complete(int to, Object value) {
        synchronized (mu) {
            if (state != PENDING) {
                return false;
            }
            settle(to, value);
            return true;
        }
    }

    /** The monitor is held. The callable goes at once: a finished task must not keep it alive. */
    private void settle(int to, Object value) {
        settled = value;
        state = to;
        callable = null;
        mu.notifyAll();
    }

    /** Takes the runner's place; the callable to run, or null when there is nothing to do. */
    private Callable<V> claim() {
        synchronized (mu) {
            if (state != PENDING || runner != null) {
                return null;
            }
            runner = Thread.currentThread();
            return callable;
        }
    }

    private void release() {
        synchronized (mu) {
            runner = null;
        }
    }

    public void run() {
        Callable<V> c = claim();
        if (c == null) {
            return;
        }
        try {
            V result;
            try {
                result = c.call();
            } catch (Throwable t) {
                setException(t);
                return;
            }
            set(result);
        } finally {
            release();
        }
    }

    protected boolean runAndReset() {
        Callable<V> c = claim();
        if (c == null) {
            return false;
        }
        try {
            try {
                c.call();
            } catch (Throwable t) {
                setException(t);
                return false;
            }
            return state == PENDING;
        } finally {
            release();
        }
    }
}
