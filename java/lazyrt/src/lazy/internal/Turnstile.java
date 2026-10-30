package lazy.internal;

import java.util.ArrayDeque;
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
 * it gets the VM, normally in the same slice of the VM lock (docs/design/threads.md, "Turns").
 *
 * <p>A newcomer in non-fair mode may overtake a queue whose head is younger than one VM lock
 * slice. That spares a thread switch per critical section while a thread loops on a contended
 * lock. Once the head is older, newcomers queue and park, so the VM reaches the head; without this
 * a looping thread that holds the lock at each safepoint starves everyone.
 */
public final class Turnstile {
    public static final int ENTERED = 0;
    public static final int TIMED_OUT = 1;
    public static final int INTERRUPTED = 2;

    private static final long SLICE_NANOS = 5_000_000L;

    /**
     * Lets the waiter in, updating the owner's state, if it can; the monitor is held. An
     * {@code Error} from here reaches the waiter's caller, and the waiter leaves the queue.
     */
    public interface Gate {
        boolean enter(Waiter w);

        /** How long the head may sleep before {@link #enter} could say yes unprompted. */
        default long patience(Waiter w) {
            return Long.MAX_VALUE;
        }
    }

    public static class Waiter {
        public final Thread thread = Thread.currentThread();
        /** What it asks for: permits, or the hold count to restore. */
        public final int count;
        /** Its entry may leave room for the next waiter, who is then woken as well. */
        public final boolean shared;
        long since;

        public Waiter(int count, boolean shared) {
            this.count = count;
            this.shared = shared;
        }
    }

    private final ArrayDeque<Waiter> queue = new ArrayDeque<>();
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

    /** Whether a newcomer may try the resource instead of queueing. */
    public boolean open() {
        Waiter head = queue.peekFirst();
        return head == null || !fair && System.nanoTime() - head.since < SLICE_NANOS;
    }

    /** Joins the queue; the monitor is held. Follow with {@link #pass} outside it. */
    public Waiter enqueue(Waiter w) {
        w.since = System.nanoTime();
        queue.addLast(w);
        return w;
    }

    /** To be called after any change that may let the head in; the monitor is held. */
    public void wakeHead() {
        Waiter head = queue.peekFirst();
        if (head != null) {
            LockSupport.unpark(head.thread);
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
                    if (admit(w)) {
                        return ENTERED;
                    }
                    if (Thread.interrupted()) {
                        if (interruptible) {
                            leave(w);
                            return INTERRUPTED;
                        }
                        // Left set, the flag would make every park below return at once.
                        flagged = true;
                    }
                    left = deadline - System.nanoTime();
                    if (timed && left <= 0) {
                        leave(w);
                        return TIMED_OUT;
                    }
                    if (queue.peekFirst() == w) {
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
        if (queue.peekFirst() != w) {
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
        queue.pollFirst();
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
        return queue.size();
    }

    public boolean contains(Thread t) {
        if (t == null) {
            throw new NullPointerException();
        }
        synchronized (monitor) {
            for (Waiter w : queue) {
                if (w.thread == t) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The queued threads whose {@code shared} is one of the two asked for. */
    public Collection<Thread> threads(boolean shared, boolean exclusive) {
        ArrayList<Thread> out = new ArrayList<>();
        synchronized (monitor) {
            for (Waiter w : queue) {
                if (w.shared ? shared : exclusive) {
                    out.add(w.thread);
                }
            }
        }
        return out;
    }
}
