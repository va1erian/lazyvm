package java.util.concurrent;

/**
 * The computations running on a {@link HashCore}, newest first. Only the core changes the list, under
 * its monitor; a claim that has left keeps its link, so a thread walking towards older claims goes on
 * reaching all those still there.
 */
final class HashClaims {
    static final class Claim {
        final int hash;
        final Object key;
        final Thread thread = Thread.currentThread();
        private Claim next;
        /** A {@code clear} ran during the computation. */
        boolean wiped;

        Claim(int hash, Object key) {
            this.hash = hash;
            this.key = key;
        }

        /** Waits out every older claim on what this one claims. Later ones see this one and wait for it. */
        void awaitOlder() {
            for (Claim c = next; c != null; c = c.next) {
                if (c.thread != thread && isOn(c)) {
                    c.awaitEnd();
                }
            }
        }

        /** Whoever claimed holds the monitor throughout: having got in is the news. */
        private void awaitEnd() {
            synchronized (this) {
                // Nothing to do inside.
            }
        }

        private boolean isOn(Claim other) {
            return hash == other.hash && (key == other.key || key.equals(other.key));
        }
    }

    private Claim newest;

    boolean any() {
        return newest != null;
    }

    /** Whether one may be on a key of this hash. Runs no user code, so it cannot tell a key from another. */
    boolean mayHold(int hash) {
        for (Claim c = newest; c != null; c = c.next) {
            if (c.hash == hash) {
                return true;
            }
        }
        return false;
    }

    void add(Claim mine) {
        mine.next = newest;
        newest = mine;
    }

    void remove(Claim mine) {
        if (newest == mine) {
            newest = mine.next;
            return;
        }
        Claim c = newest;
        while (c.next != mine) {
            c = c.next;
        }
        c.next = mine.next;
    }

    /** Waits out every claim there is now, except this thread's own. */
    void awaitOthers() {
        Thread me = Thread.currentThread();
        for (Claim c = newest; c != null; c = c.next) {
            if (c.thread != me) {
                c.awaitEnd();
            }
        }
    }

    void markWiped() {
        for (Claim c = newest; c != null; c = c.next) {
            c.wiped = true;
        }
    }
}
