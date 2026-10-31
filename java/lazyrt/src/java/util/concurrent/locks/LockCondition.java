package java.util.concurrent.locks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import lazy.internal.Turnstile;

/**
 * A list of waiters, guarded like everything else by the lock's turnstile. {@code signal} moves a
 * waiter from here to the lock's queue, and a waiter that stops waiting on its own (interrupt,
 * timeout) moves itself; in both cases it goes on to pass the queue as an uninterruptible
 * acquisition, with its old hold count, before it can return or throw.
 */
final class LockCondition implements Condition {
    final LockCore core;
    private final Turnstile turnstile;
    private final Turnstile.Line waiters = new Turnstile.Line();

    LockCondition(LockCore core) {
        this.core = core;
        this.turnstile = core.turnstile;
    }

    public void await() throws InterruptedException {
        if (block(true, false, 0) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
    }

    public void awaitUninterruptibly() {
        block(false, false, 0);
    }

    public long awaitNanos(long nanosTimeout) throws InterruptedException {
        // Not clamped, for the answer alone: the oracle's for a huge negative wait wraps to positive.
        long deadline = System.nanoTime() + nanosTimeout;
        if (block(true, true, nanosTimeout) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return deadline - System.nanoTime();
    }

    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        return timedAwait(unit.toNanos(time));
    }

    public boolean awaitUntil(Date deadline) throws InterruptedException {
        long at = deadline.getTime();
        long now = System.currentTimeMillis();
        // A date far enough in the past would wrap the subtraction into a far future.
        return timedAwait(at <= now ? 0 : TimeUnit.MILLISECONDS.toNanos(at - now));
    }

    private boolean timedAwait(long nanos) throws InterruptedException {
        int result = block(true, true, nanos);
        if (result == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return result == Turnstile.ENTERED;
    }

    /** The whole wait; the lock is held again on return. ENTERED here means it was signalled. */
    private int block(boolean interruptible, boolean timed, long nanos) {
        long deadline = Turnstile.deadline(nanos);
        Turnstile.Waiter w;
        // On the oracle the flag comes before the ownership check.
        if (interruptible && Thread.interrupted()) {
            return Turnstile.INTERRUPTED;
        }
        synchronized (turnstile) {
            core.checkOwner();
            w = new Turnstile.Waiter(core.ownerHolds(), false);
            waiters.addLast(w);
            core.releaseAll();
        }
        int result = awaitSignal(w, interruptible, timed, deadline);
        turnstile.pass(w, false, false, 0);
        if (result == Turnstile.INTERRUPTED) {
            // An interrupt during the re-acquisition is covered by the exception about to be thrown.
            Thread.interrupted();
        }
        return result;
    }

    private int awaitSignal(Turnstile.Waiter w, boolean interruptible, boolean timed, long deadline) {
        boolean flagged = false;
        int result;
        for (;;) {
            long left;
            synchronized (turnstile) {
                if (!waiters.holds(w)) {
                    result = Turnstile.ENTERED;
                    break;
                }
                if (Thread.interrupted()) {
                    if (interruptible) {
                        result = moveToQueue(w, Turnstile.INTERRUPTED);
                        break;
                    }
                    flagged = true;
                }
                left = deadline - System.nanoTime();
                if (timed && left <= 0) {
                    result = moveToQueue(w, Turnstile.TIMED_OUT);
                    break;
                }
            }
            if (timed) {
                LockSupport.parkNanos(this, left);
            } else {
                LockSupport.park(this);
            }
        }
        if (flagged) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private int moveToQueue(Turnstile.Waiter w, int result) {
        waiters.remove(w);
        turnstile.enqueue(w);
        return result;
    }

    public void signal() {
        synchronized (turnstile) {
            core.checkOwner();
            if (waiters.first() != null) {
                moveToQueue(waiters.first(), Turnstile.ENTERED);
            }
        }
    }

    public void signalAll() {
        synchronized (turnstile) {
            core.checkOwner();
            while (waiters.first() != null) {
                moveToQueue(waiters.first(), Turnstile.ENTERED);
            }
        }
    }

    int length() {
        return waiters.size();
    }

    Collection<Thread> threads() {
        ArrayList<Thread> out = new ArrayList<>();
        synchronized (turnstile) {
            for (Turnstile.Waiter w = waiters.first(); w != null; w = w.next()) {
                out.add(w.thread);
            }
        }
        return out;
    }
}
