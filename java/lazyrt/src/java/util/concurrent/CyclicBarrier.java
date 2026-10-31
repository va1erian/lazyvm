package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Arrivals count down under a lock. A blocked arrival is told directly how its round ended, as it
 * may only get the lock back after later rounds have come and gone. The barrier action runs holding
 * the lock, so during it the others wait in WAITING state, the getters too (concurrent.md,
 * "CyclicBarrier").
 */
public class CyclicBarrier {
    private static final class Arrival {
        Arrival next;
        boolean over;
        boolean broken;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tripped = lock.newCondition();
    private final int parties;
    private final Runnable action;
    private int missing;
    private boolean broken;
    /** Those blocked in the current round. */
    private Arrival blocked;

    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) {
            throw new IllegalArgumentException();
        }
        this.parties = parties;
        this.action = barrierAction;
        this.missing = parties;
    }

    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    public int getParties() {
        return parties;
    }

    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return arrive(false, 0);
        } catch (TimeoutException e) {
            throw new InternalError(e);
        }
    }

    public int await(long timeout, TimeUnit unit)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        return arrive(true, unit.toNanos(timeout));
    }

    private int arrive(boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        lock.lock();
        try {
            if (broken) {
                throw new BrokenBarrierException();
            }
            if (Thread.interrupted()) {
                endRound(true);
                throw new InterruptedException();
            }
            // Before the count moves: running out of memory here must leave the round as it was.
            Arrival me = missing == 1 ? null : new Arrival();
            int index = --missing;
            if (index == 0) {
                finishRound();
                return 0;
            }
            me.next = blocked;
            blocked = me;
            waitFor(me, timed, nanos);
            return index;
        } finally {
            lock.unlock();
        }
    }

    /** The last arrival: the action, then everyone goes; a throwing action breaks the round. */
    private void finishRound() {
        boolean done = false;
        try {
            if (action != null) {
                action.run();
            }
            done = true;
        } finally {
            endRound(!done);
        }
    }

    private void waitFor(Arrival me, boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        long left = nanos;
        for (;;) {
            try {
                if (!timed) {
                    tripped.await();
                } else if (left > 0) {
                    left = tripped.awaitNanos(left);
                }
            } catch (InterruptedException e) {
                if (!me.over) {
                    endRound(true);
                    throw e;
                }
                // The round was over before the interrupt: it stays pending for what comes next.
                Thread.currentThread().interrupt();
            }
            if (me.over) {
                if (me.broken) {
                    throw new BrokenBarrierException();
                }
                return;
            }
            if (timed && left <= 0) {
                endRound(true);
                throw new TimeoutException();
            }
        }
    }

    /** Tells those blocked how it ended. A broken barrier stays so until {@link #reset}. */
    private void endRound(boolean asBroken) {
        for (Arrival a = blocked; a != null; a = a.next) {
            a.over = true;
            a.broken = asBroken;
        }
        blocked = null;
        broken = asBroken;
        missing = parties;
        tripped.signalAll();
    }

    public boolean isBroken() {
        lock.lock();
        try {
            return broken;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            endRound(true);
            broken = false;
        } finally {
            lock.unlock();
        }
    }

    public int getNumberWaiting() {
        lock.lock();
        try {
            return parties - missing;
        } finally {
            lock.unlock();
        }
    }
}
