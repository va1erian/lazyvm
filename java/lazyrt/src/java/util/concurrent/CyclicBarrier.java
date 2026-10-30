package java.util.concurrent;

/**
 * Arrivals count down on a private monitor. A thread waits on the {@link Round} it joined:
 * the round ends when the last party arrives, or broken. The barrier action runs under the
 * monitor, so nobody can arrive during it, while the getters take no lock and never wait for it.
 */
public class CyclicBarrier {
    /** One trip of the barrier; a reset or a completed trip starts a new one. */
    private static final class Round {
        boolean broken;
    }

    private final Object mu = new Object();
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
        long deadline = System.nanoTime() + nanos;
        synchronized (mu) {
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
            waitFor(mine, timed, deadline);
            return index;
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

    private void waitFor(Round mine, boolean timed, long deadline)
            throws InterruptedException, BrokenBarrierException, TimeoutException {
        for (;;) {
            try {
                if (timed) {
                    TimeUnit.NANOSECONDS.timedWait(mu, deadline - System.nanoTime());
                } else {
                    mu.wait();
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
            if (timed && deadline - System.nanoTime() <= 0) {
                breakRound();
                throw new TimeoutException();
            }
        }
    }

    private void breakRound() {
        round.broken = true;
        missing = parties;
        mu.notifyAll();
    }

    private void startRound() {
        round = new Round();
        missing = parties;
        mu.notifyAll();
    }

    public boolean isBroken() {
        return round.broken;
    }

    public void reset() {
        synchronized (mu) {
            breakRound();
            startRound();
        }
    }

    public int getNumberWaiting() {
        return parties - missing;
    }
}
