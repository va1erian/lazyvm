package java.util.concurrent;

import lazy.internal.Turnstile;

/**
 * Every phaser of a tree shares its root's monitor, and the phase lives in the root alone, so all
 * tiers advance in one step. Arrivals are counted per node and only count for the phase they were
 * made in: a node's count goes stale, rather than being reset, when the phase moves on. A node
 * whose parties have all arrived arrives once at its parent; one left with no parties leaves it.
 * {@code onAdvance} runs outside the monitor, as nothing may keep the others out while user code
 * runs: during it the root counts as fully arrived, so an arrival is an error and a registration
 * waits for the phase to change (concurrent.md "Phaser": "hook ..." lines).
 */
public class Phaser {
    private static final int PARTY_LIMIT = 65535;

    private final Object mu;
    private final Phaser parent;
    private final Phaser root;
    private volatile int phase;
    private int parties;
    private int arrived;
    private int arrivedIn;
    /** At the root: the hook is running or has thrown, and the phase has not moved on. */
    private boolean inHook;
    /** At the root: a hook ended it, so the arrivals of the phase before still show. */
    private boolean endedByHook;
    /** Its first party is being registered with the parent, which can wait: others wait for that. */
    private boolean joining;

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
        if (parties < 0 || parties > PARTY_LIMIT) {
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
            awaitRoom();
            if (root.phase >= 0 && n > PARTY_LIMIT - parties) {
                throw new IllegalStateException("Attempt to register more than 65535 parties for " + this);
            }
            if (root.phase >= 0 && parties == 0 && parent != null) {
                joinParent();
            }
            // Joining may have waited, and a termination does not change the count.
            if (root.phase >= 0) {
                parties += n;
            }
            return root.phase;
        }
    }

    /**
     * Waits, deaf to interrupts, until a registration may go in or the phaser has ended. A child
     * whose parties have all arrived cannot take a new one in this phase: its arrival would count
     * twice. And nothing registers during the hook. Javadoc: this may wait.
     */
    private void awaitRoom() {
        boolean flagged = false;
        while (root.phase >= 0 && (root.inHook || joining || parties > 0 && arrivedNow() == parties)) {
            try {
                mu.wait();
            } catch (InterruptedException e) {
                flagged = true;
            }
        }
        if (flagged) {
            Thread.currentThread().interrupt();
        }
    }

    /** Parties is still 0 during this, and the monitor may be let go: one registers, the others wait. */
    private void joinParent() {
        joining = true;
        try {
            parent.add(1);
        } finally {
            joining = false;
            mu.notifyAll();
        }
    }

    public int arrive() {
        return arrival(false);
    }

    public int arriveAndDeregister() {
        return arrival(true);
    }

    public int arriveAndAwaitAdvance() {
        int now;
        synchronized (mu) {
            now = root.phase;
            if (now < 0) {
                return now;
            }
            if (!count(false, now)) {
                return awaitChange(now);
            }
        }
        return root.advance(now);
    }

    private int arrival(boolean leaving) {
        int now;
        synchronized (mu) {
            now = root.phase;
            if (now < 0 || !count(leaving, now)) {
                return now;
            }
        }
        root.advance(now);
        return now;
    }

    /** An arrival or departure of a registered party; true if it was the last the tree waited for. */
    private boolean count(boolean leaving, int now) {
        if (parties - arrivedNow() <= 0) {
            throw new IllegalStateException("Attempted arrival of unregistered party for " + this);
        }
        return step(leaving, now);
    }

    /** Counts one arrival or departure here and up the tree; true if the whole tree has arrived. */
    private boolean step(boolean leaving, int now) {
        arrived = arrivedNow();
        arrivedIn = now;
        if (leaving) {
            parties--;
        } else {
            arrived++;
        }
        if (parties > arrived) {
            return false;
        }
        if (parent != null) {
            return parent.step(parties == 0, now);
        }
        inHook = true;
        return true;
    }

    /** Runs the hook and moves on. The next phase's number is the answer even when the hook ended it. */
    private int advance(int now) {
        boolean end = onAdvance(now, parties);
        int next = (now + 1) & Integer.MAX_VALUE;
        synchronized (mu) {
            // forceTermination during the hook wins: the phase stays as it left it.
            if (phase < 0) {
                return phase;
            }
            phase = end ? next | Integer.MIN_VALUE : next;
            endedByHook = end;
            inHook = false;
            mu.notifyAll();
            return next;
        }
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
        long deadline = Turnstile.deadline(unit.toNanos(timeout));
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
        int in = root.endedByHook ? arrivedIn + 1 : arrivedIn;
        return ((in ^ root.phase) & Integer.MAX_VALUE) == 0 ? arrived : 0;
    }

    public String toString() {
        return super.toString() + "[phase = " + root.phase + " parties = " + parties
            + " arrived = " + arrivedNow() + "]";
    }
}
