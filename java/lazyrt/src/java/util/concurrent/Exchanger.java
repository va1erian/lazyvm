package java.util.concurrent;

/** One waiting offer at a time on a private monitor; the next arrival takes it and answers it. */
public class Exchanger<V> {
    private static final class Offer {
        final Object given;
        Object answer;
        boolean answered;

        Offer(Object given) {
            this.given = given;
        }
    }

    private final Object mu = new Object();
    private Offer waiting;

    public Exchanger() {
    }

    public V exchange(V x) throws InterruptedException {
        try {
            return swap(x, false, 0);
        } catch (TimeoutException e) {
            throw new InternalError(e);
        }
    }

    public V exchange(V x, long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        return swap(x, true, unit.toNanos(timeout));
    }

    @SuppressWarnings("unchecked")
    private V swap(Object x, boolean timed, long nanos) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + nanos;
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (mu) {
            Offer theirs = waiting;
            if (theirs != null) {
                waiting = null;
                theirs.answer = x;
                theirs.answered = true;
                mu.notifyAll();
                return (V) theirs.given;
            }
            Offer mine = new Offer(x);
            waiting = mine;
            awaitAnswer(mine, timed, deadline);
            return (V) mine.answer;
        }
    }

    private void awaitAnswer(Offer mine, boolean timed, long deadline)
            throws InterruptedException, TimeoutException {
        while (!mine.answered) {
            long left = deadline - System.nanoTime();
            if (timed && left <= 0) {
                waiting = null;
                throw new TimeoutException();
            }
            try {
                if (timed) {
                    TimeUnit.NANOSECONDS.timedWait(mu, left);
                } else {
                    mu.wait();
                }
            } catch (InterruptedException e) {
                if (!mine.answered) {
                    waiting = null;
                    throw e;
                }
                // Too late to withdraw: the other side has our value, so finish and keep the flag.
                Thread.currentThread().interrupt();
            }
        }
    }
}
