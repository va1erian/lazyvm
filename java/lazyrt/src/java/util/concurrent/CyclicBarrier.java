package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Arrivals count down under a lock. A thread waits on the {@link Round} it joined: the round ends
 * when the last party arrives, or broken. The barrier action runs holding the lock, so during it
 * the others wait in WAITING state, the getters too (concurrent.md, "CyclicBarrier").
 */
public class CyclicBarrier {
    /** One trip of the barrier; a reset or a completed trip starts a new one. */
    private static final class Round {
        boolean broken;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition tripped = lock.newCondition();
    private final int parties;
    private final Runnable action;
    private int missing;
    private Round round = new Round();

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
            Round mine = round;
            if (mine.broken) {
                throw new BrokenBarrierException();
            }
            if (Thread.interrupted()) {
                breakRound();
                throw new InterruptedException();
            }
            int index = --missing;
            if (index == 0) {
                finishRound();
                return 0;
            }
            waitFor(mine, timed, nanos);
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
            if (done) {
                startRound();
            } else {
                breakRound();
            }
        }
    }

    private void waitFor(Round mine, boolean timed, long nanos)
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
                if (mine == round && !mine.broken) {
                    breakRound();
                    throw e;
                }
                // The round was over before the interrupt: it stays pending for what comes next.
                Thread.currentThread().interrupt();
            }
            if (mine.broken) {
                throw new BrokenBarrierException();
            }
            if (mine != round) {
                return;
            }
            if (timed && left <= 0) {
                breakRound();
                throw new TimeoutException();
            }
        }
    }

    private void breakRound() {
        round.broken = true;
        missing = parties;
        tripped.signalAll();
    }

    private void startRound() {
        round = new Round();
        missing = parties;
        tripped.signalAll();
    }

    public boolean isBroken() {
        lock.lock();
        try {
            return round.broken;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            breakRound();
            startRound();
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
