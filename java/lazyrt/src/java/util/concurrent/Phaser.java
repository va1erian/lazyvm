package java.util.concurrent;

/**
 * Every phaser of a tree shares its root's monitor, and the phase lives in the root alone, so all
 * tiers advance in one step. Arrivals are counted per node and only count for the phase they were
 * made in: a node's count goes stale, rather than being reset, when the phase moves on. A node
 * whose parties have all arrived arrives once at its parent; one left with no parties leaves it.
 * {@code onAdvance} runs under the monitor (Javadoc: other calls from it are unspecified), and the
 * getters take no lock, so they never wait for it.
 */
public class Phaser {
    private static final int MAX_PARTIES = 65535;

    private final Object mu;
    private final Phaser parent;
    private final Phaser root;
    private volatile int phase;
    private int parties;
    private int arrived;
    private int arrivedIn;

    public Phaser() {
        this(null, 0);
    }

    public Phaser(int parties) {
        this(null, parties);
    }

    public Phaser(Phaser parent) {
        this(parent, 0);
    }

    public Phaser(Phaser parent, int parties) {
        if (parties < 0 || parties > MAX_PARTIES) {
            throw new IllegalArgumentException("Illegal number of parties");
        }
        this.parent = parent;
        this.root = parent == null ? this : parent.root;
        this.mu = parent == null ? new Object() : parent.mu;
        this.parties = parties;
        if (parent != null && parties != 0) {
            parent.add(1);
        }
    }

    public int register() {
        return add(1);
    }

    public int bulkRegister(int parties) {
        if (parties < 0) {
            throw new IllegalArgumentException();
        }
        return add(parties);
    }

    private int add(int n) {
        synchronized (mu) {
            if (n == 0 || root.phase < 0) {
                return root.phase;
            }
            // A child that has arrived at its parent cannot take a new party in this phase: its
            // arrival would count twice. Javadoc: registering may wait for the advance.
            if (parent != null && parties > 0 && arrivedNow() == parties) {
                awaitChange(root.phase);
                if (root.phase < 0) {
                    return root.phase;
                }
            }
            if (n > MAX_PARTIES - parties) {
                throw new IllegalStateException("Attempt to register more than 65535 parties for " + this);
            }
            if (parties == 0 && parent != null) {
                parent.add(1);
            }
            parties += n;
            return root.phase;
        }
    }

    public int arrive() {
        return arrival(false);
    }

    public int arriveAndDeregister() {
        return arrival(true);
    }

    public int arriveAndAwaitAdvance() {
        synchronized (mu) {
            int now = arrival(false);
            return now < 0 ? now : awaitChange(now);
        }
    }

    private int arrival(boolean leaving) {
        synchronized (mu) {
            int now = root.phase;
            if (now < 0) {
                return now;
            }
            if (parties - arrivedNow() <= 0) {
                throw new IllegalStateException("Attempted arrival of unregistered party for " + this);
            }
            step(leaving, now);
            return now;
        }
    }

    private void step(boolean leaving, int now) {
        arrived = arrivedNow();
        arrivedIn = now;
        if (leaving) {
            parties--;
        } else {
            arrived++;
        }
        if (parties > arrived) {
            return;
        }
        if (parent != null) {
            parent.step(parties == 0, now);
        } else {
            advance(now);
        }
    }

    private void advance(int now) {
        boolean end = onAdvance(now, parties);
        int next = (now + 1) & Integer.MAX_VALUE;
        phase = end ? next | Integer.MIN_VALUE : next;
        mu.notifyAll();
    }

    public int awaitAdvance(int phase) {
        if (phase < 0) {
            return phase;
        }
        synchronized (mu) {
            return root.phase == phase ? awaitChange(phase) : root.phase;
        }
    }

    /** Waits, deaf to interrupts, until the phase is no longer `now`; returns the new one. */
    private int awaitChange(int now) {
        boolean flagged = false;
        while (root.phase == now) {
            try {
                mu.wait();
            } catch (InterruptedException e) {
                flagged = true;
            }
        }
        if (flagged) {
            Thread.currentThread().interrupt();
        }
        return root.phase;
    }

    public int awaitAdvanceInterruptibly(int phase) throws InterruptedException {
        if (phase < 0) {
            return phase;
        }
        synchronized (mu) {
            while (root.phase == phase) {
                mu.wait();
            }
            return root.phase;
        }
    }

    public int awaitAdvanceInterruptibly(int phase, long timeout, TimeUnit unit)
            throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (phase < 0) {
            return phase;
        }
        synchronized (mu) {
            while (root.phase == phase) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                TimeUnit.NANOSECONDS.timedWait(mu, left);
            }
            return root.phase;
        }
    }

    public void forceTermination() {
        synchronized (mu) {
            root.phase |= Integer.MIN_VALUE;
            mu.notifyAll();
        }
    }

    public final int getPhase() {
        return root.phase;
    }

    public int getRegisteredParties() {
        return parties;
    }

    public int getArrivedParties() {
        return arrivedNow();
    }

    public int getUnarrivedParties() {
        return parties - arrivedNow();
    }

    public Phaser getParent() {
        return parent;
    }

    public Phaser getRoot() {
        return root;
    }

    public boolean isTerminated() {
        return root.phase < 0;
    }

    protected boolean onAdvance(int phase, int registeredParties) {
        return registeredParties == 0;
    }

    private int arrivedNow() {
        // forceTermination sets only the sign bit: the arrivals of that phase still show.
        return ((arrivedIn ^ root.phase) & Integer.MAX_VALUE) == 0 ? arrived : 0;
    }

    public String toString() {
        return super.toString() + "[phase = " + root.phase + " parties = " + parties
            + " arrived = " + arrivedNow() + "]";
    }
}
