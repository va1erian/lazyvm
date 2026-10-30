package lazy.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.locks.LockSupport;

/**
 * The queue behind the locks and {@code Semaphore}: blocked threads in arrival order, where only
 * the head may go through its {@link Gate}. One monitor guards the queue and also the state of
 * whoever uses it: the turnstile's own, so a user keeps that state in {@code synchronized
 * (turnstile)} sections, or one the owner gives when it has several lines (a queue's takers and
 * putters) over the same state.
 * Nothing is handed over: whoever releases wakes the head, which comes back through the gate when
 * it gets the VM (docs/design/threads.md).
 *
 * <p>A newcomer in non-fair mode may overtake a queue whose head is younger than one VM lock
 * slice. That spares a thread switch per critical section while a thread loops on a contended
 * lock. Once the head is older, newcomers queue and park, so the VM reaches the head; without this
 * a looping thread that holds the lock at each safepoint starves everyone. Overtaking stays allowed
 * at any age when the head could not go anyway, and for a wait too short to see the head go.
 *
 * <p>Queueing allocates nothing: a waiter is its own list link, so running out of memory can only
 * stop a thread before it joins, never leave a list half changed.
 */
public final class Turnstile {
    public static final int ENTERED = 0;
    public static final int TIMED_OUT = 1;
    public static final int INTERRUPTED = 2;

    private static final long SLICE_NANOS = 5_000_000L;

    /**
     * Lets the waiter in, updating the owner's state, if it can; the monitor is held. Anything
     * thrown from {@link #enter} reaches the waiter's caller, and the waiter leaves the queue.
     */
    public interface Gate {
        boolean enter(Waiter w);

        /**
         * Asked right after {@link #enter} said no: how long the head may sleep before it could say
         * yes unprompted. It answers from what {@code enter} saw, so it runs no user code and cannot
         * throw: the waiter is not in a position to leave the queue here.
         */
        default long patience(Waiter w) {
            return Long.MAX_VALUE;
        }

        /** Whether {@link #enter} would let this head in now; one that would not holds up no newcomer. */
        default boolean ready(Waiter w) {
            return true;
        }
    }

    public static class Waiter {
        public final Thread thread = Thread.currentThread();
        /** What it asks for: permits, or the hold count to restore. */
        public final int count;
        /** Its entry may leave room for the next waiter, who is then woken as well. */
        public final boolean shared;
        long since;
        private Line line;
        private Waiter before;
        private Waiter after;

        public Waiter(int count, boolean shared) {
            this.count = count;
            this.shared = shared;
        }

        /** The one behind it in its line. */
        public Waiter next() {
            return after;
        }
    }

    /** Waiters in arrival order, linked through themselves; a waiter is in one line at a time. */
    public static final class Line {
        private Waiter first;
        private Waiter last;
        private int size;

        public Waiter first() {
            return first;
        }

        public int size() {
            return size;
        }

        public boolean holds(Waiter w) {
            return w.line == this;
        }

        public void addLast(Waiter w) {
            w.line = this;
            w.before = last;
            if (last == null) {
                first = w;
            } else {
                last.after = w;
            }
            last = w;
            size++;
        }

        public void remove(Waiter w) {
            if (w.before == null) {
                first = w.after;
            } else {
                w.before.after = w.after;
            }
            if (w.after == null) {
                last = w.before;
            } else {
                w.after.before = w.before;
            }
            w.line = null;
            w.before = null;
            w.after = null;
            size--;
        }
    }

    private final Line queue = new Line();
    private final Gate gate;
    private final Object monitor;
    public final boolean fair;

    public Turnstile(boolean fair, Gate gate) {
        this.fair = fair;
        this.gate = gate;
        this.monitor = this;
    }

    public Turnstile(boolean fair, Gate gate, Object monitor) {
        this.fair = fair;
        this.gate = gate;
        this.monitor = monitor;
    }

    /** A deadline for {@link #pass}. Clamped, as a huge negative wait would wrap into the far future. */
    public static long deadline(long nanos) {
        return System.nanoTime() + Math.max(nanos, 0);
    }

    /** Whether a newcomer may try the resource instead of queueing. */
    public boolean open() {
        Waiter head = queue.first;
        return head == null || !fair && (System.nanoTime() - head.since < SLICE_NANOS || !gate.ready(head));
    }

    /** The same for one that will wait at most this long: the head would not have had its turn by then. */
    public boolean open(boolean timed, long nanos) {
        return open() || !fair && timed && nanos < SLICE_NANOS;
    }

    public Waiter head() {
        return queue.first;
    }

    /** Joins the queue; the monitor is held. Follow with {@link #pass} outside it. */
    public Waiter enqueue(Waiter w) {
        w.since = System.nanoTime();
        queue.addLast(w);
        return w;
    }

    /** To be called after any change that may let the head in; the monitor is held. */
    public void wakeHead() {
        if (queue.first != null) {
            LockSupport.unpark(queue.first.thread);
        }
    }

    /**
     * Blocks the queued waiter until it has gone through the gate, was interrupted (when
     * {@code interruptible}) or reached {@code deadline} (when {@code timed}), and says which. A
     * flag it ignored is set again on return. Blocked means parked, so a thread here reports
     * WAITING or TIMED_WAITING.
     */
    public int pass(Waiter w, boolean interruptible, boolean timed, long deadline) {
        boolean flagged = false;
        try {
            for (;;) {
                long left;
                synchronized (monitor) {
                    // The flag first: an interrupted waiter does not ask the gate again.
                    if (Thread.interrupted()) {
                        if (interruptible) {
                            leave(w);
                            return INTERRUPTED;
                        }
                        // Left set, the flag would make every park below return at once.
                        flagged = true;
                    }
                    if (admit(w)) {
                        return ENTERED;
                    }
                    left = deadline - System.nanoTime();
                    if (timed && left <= 0) {
                        leave(w);
                        return TIMED_OUT;
                    }
                    if (queue.first == w) {
                        long patience = gate.patience(w);
                        left = timed ? Math.min(left, patience) : patience;
                    } else if (!timed) {
                        left = Long.MAX_VALUE;
                    }
                }
                if (left != Long.MAX_VALUE) {
                    LockSupport.parkNanos(this, left);
                } else {
                    LockSupport.park(this);
                }
            }
        } finally {
            if (flagged) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean admit(Waiter w) {
        if (queue.first != w) {
            return false;
        }
        try {
            if (!gate.enter(w)) {
                return false;
            }
        } catch (RuntimeException | Error e) {
            leave(w);
            throw e;
        }
        queue.remove(w);
        if (w.shared) {
            wakeHead();
        }
        return true;
    }

    private void leave(Waiter w) {
        queue.remove(w);
        wakeHead();
    }

    public int length() {
        return queue.size;
    }

    public boolean contains(Thread t) {
        if (t == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            for (Waiter w = queue.first; w != null; w = w.after) {
                if (w.thread == t) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The queued threads whose {@code shared} is one of the two asked for, latest arrival first. */
    public Collection<Thread> threads(boolean shared, boolean exclusive) {
        ArrayList<Thread> out = new ArrayList<>();
        synchronized (monitor) {
            for (Waiter w = queue.last; w != null; w = w.before) {
                if (w.shared ? shared : exclusive) {
                    out.add(w.thread);
                }
            }
        }
        return out;
    }
}
