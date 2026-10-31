package java.util.concurrent;

import java.util.Collection;
import lazy.internal.Turnstile;

/** A permit count guarded by a {@link Turnstile}; see there for how waiters take turns. */
public class Semaphore implements java.io.Serializable {
    /** The permits: a nested class so that the semaphore itself shows no extra interface. */
    private static final class Permits implements Turnstile.Gate {
        int count;

        Permits(int count) {
            this.count = count;
        }

        public boolean enter(Turnstile.Waiter w) {
            return take(w.count);
        }

        public boolean ready(Turnstile.Waiter w) {
            return count >= w.count;
        }

        boolean take(int n) {
            if (count < n) {
                return false;
            }
            count -= n;
            return true;
        }
    }

    private final Permits permits;
    private final Turnstile turnstile;

    public Semaphore(int permits) {
        this(permits, false);
    }

    public Semaphore(int permits, boolean fair) {
        this.permits = new Permits(permits);
        this.turnstile = new Turnstile(fair, this.permits);
    }

    private static void check(int n) {
        if (n < 0) {
            throw new IllegalArgumentException();
        }
    }

    private int take(int n, boolean interruptible, boolean timed, long nanos) {
        long deadline = Turnstile.deadline(nanos);
        Turnstile.Waiter w;
        synchronized (turnstile) {
            if (turnstile.open(timed, nanos) && permits.take(n)) {
                return Turnstile.ENTERED;
            }
            w = turnstile.enqueue(new Turnstile.Waiter(n, true));
        }
        return turnstile.pass(w, interruptible, timed, deadline);
    }

    public void acquire() throws InterruptedException {
        acquire(1);
    }

    public void acquire(int permits) throws InterruptedException {
        check(permits);
        if (Thread.interrupted() || take(permits, true, false, 0) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
    }

    public void acquireUninterruptibly() {
        acquireUninterruptibly(1);
    }

    public void acquireUninterruptibly(int permits) {
        check(permits);
        take(permits, false, false, 0);
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int permits) {
        check(permits);
        synchronized (turnstile) {
            return this.permits.take(permits);
        }
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    public boolean tryAcquire(int permits, long timeout, TimeUnit unit) throws InterruptedException {
        check(permits);
        long nanos = unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        int result = take(permits, true, true, nanos);
        if (result == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return result == Turnstile.ENTERED;
    }

    public void release() {
        release(1);
    }

    public void release(int permits) {
        check(permits);
        synchronized (turnstile) {
            int now = this.permits.count + permits;
            if (now < this.permits.count) {
                throw new Error("Maximum permit count exceeded");
            }
            this.permits.count = now;
            turnstile.wakeHead();
        }
    }

    public int availablePermits() {
        return permits.count;
    }

    public int drainPermits() {
        synchronized (turnstile) {
            int had = permits.count;
            permits.count = 0;
            return had;
        }
    }

    protected void reducePermits(int reduction) {
        check(reduction);
        synchronized (turnstile) {
            int now = permits.count - reduction;
            if (now > permits.count) {
                throw new Error("Permit count underflow");
            }
            permits.count = now;
        }
    }

    public boolean isFair() {
        return turnstile.fair;
    }

    public final boolean hasQueuedThreads() {
        return turnstile.length() != 0;
    }

    public final int getQueueLength() {
        return turnstile.length();
    }

    protected Collection<Thread> getQueuedThreads() {
        return turnstile.threads(true, true);
    }

    public String toString() {
        return super.toString() + "[Permits = " + permits.count + "]";
    }
}
