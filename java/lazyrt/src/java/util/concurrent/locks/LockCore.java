package java.util.concurrent.locks;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.concurrent.TimeUnit;
import lazy.internal.Turnstile;

/**
 * The one lock behind {@code ReentrantLock} (which never takes the read side) and
 * {@code ReentrantReadWriteLock}: an owner with a hold count, read holds per thread, and a
 * {@link Turnstile} for those who must wait. All state changes hold the turnstile's monitor. The
 * plain getters do not need it: one thread runs at a time, and a field read cannot tear.
 */
final class LockCore implements Turnstile.Gate {
    final Turnstile turnstile;
    private final int limit;
    private Thread owner;
    private int writeHolds;
    private int readHolds;
    /** Made by the first reader: most locks never have one. */
    private IdentityHashMap<Thread, int[]> readers;

    LockCore(boolean fair, int limit) {
        this.turnstile = new Turnstile(fair, this);
        this.limit = limit;
    }

    public boolean enter(Turnstile.Waiter w) {
        if (w.shared) {
            if (owner != null) {
                return false;
            }
            addRead(w.thread);
            return true;
        }
        if (owner != null || readHolds != 0) {
            return false;
        }
        owner = w.thread;
        writeHolds = w.count;
        // Back from a condition wait, its own read holds count again (see releaseAll).
        int[] own = holdsOf(w.thread);
        readHolds = own == null ? 0 : own[0];
        return true;
    }

    private boolean tryEnter(boolean shared, boolean barge) {
        return shared ? tryRead(barge) : tryWrite(barge);
    }

    private boolean tryRead(boolean barge) {
        Thread me = Thread.currentThread();
        // A thread holding the lock never queues for more of it, or it would wait for itself.
        boolean mine = owner == me || holdsOf(me) != null;
        if (!mine && (owner != null || !barge && !turnstile.open())) {
            return false;
        }
        addRead(me);
        return true;
    }

    private boolean tryWrite(boolean barge) {
        Thread me = Thread.currentThread();
        if (owner == me) {
            if (writeHolds == limit) {
                throw new Error("Maximum lock count exceeded");
            }
            writeHolds++;
            return true;
        }
        if (owner != null || readHolds != 0 || !barge && !turnstile.open()) {
            return false;
        }
        owner = me;
        writeHolds = 1;
        return true;
    }

    private int[] holdsOf(Thread t) {
        return readers == null ? null : readers.get(t);
    }

    private void addRead(Thread t) {
        if (readHolds == limit) {
            throw new Error("Maximum lock count exceeded");
        }
        int[] holds = holdsOf(t);
        if (holds == null) {
            if (readers == null) {
                readers = new IdentityHashMap<>();
            }
            holds = new int[1];
            readers.put(t, holds);
        }
        holds[0]++;
        readHolds++;
    }

    private int acquire(boolean shared, boolean interruptible, boolean timed, long nanos) {
        long deadline = System.nanoTime() + nanos;
        Turnstile.Waiter w;
        synchronized (turnstile) {
            if (tryEnter(shared, false)) {
                return Turnstile.ENTERED;
            }
            w = turnstile.enqueue(new Turnstile.Waiter(1, shared));
        }
        return turnstile.pass(w, interruptible, timed, deadline);
    }

    void lock(boolean shared) {
        acquire(shared, false, false, 0);
    }

    void lockInterruptibly(boolean shared) throws InterruptedException {
        if (Thread.interrupted() || acquire(shared, true, false, 0) == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
    }

    boolean tryLock(boolean shared) {
        synchronized (turnstile) {
            return tryEnter(shared, true);
        }
    }

    boolean tryLock(boolean shared, long time, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(time);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        int result = acquire(shared, true, true, nanos);
        if (result == Turnstile.INTERRUPTED) {
            throw new InterruptedException();
        }
        return result == Turnstile.ENTERED;
    }

    void unlock() {
        synchronized (turnstile) {
            checkOwner();
            if (--writeHolds == 0) {
                owner = null;
                turnstile.wakeHead();
            }
        }
    }

    void unlockRead() {
        synchronized (turnstile) {
            Thread me = Thread.currentThread();
            int[] holds = holdsOf(me);
            if (holds == null) {
                throw new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread");
            }
            if (--holds[0] == 0) {
                readers.remove(me);
            }
            readHolds--;
            if (readHolds == 0) {
                turnstile.wakeHead();
            }
        }
    }

    /**
     * For a condition's wait: gives up the whole lock. The owner's read holds stop counting too,
     * as they do on the oracle, where another thread can take the write lock during the wait.
     * The owner is the only reader while it writes, so its entry in {@code readers} restores them.
     */
    void releaseAll() {
        writeHolds = 0;
        readHolds = 0;
        owner = null;
        turnstile.wakeHead();
    }

    void checkOwner() {
        if (owner != Thread.currentThread()) {
            throw new IllegalMonitorStateException();
        }
    }

    Condition newCondition() {
        return new LockCondition(this);
    }

    /** The argument of a condition query, checked in the order concurrent.md "Locks" gives. */
    LockCondition checked(Condition c) {
        if (c == null) {
            throw new NullPointerException();
        }
        if (!(c instanceof LockCondition)) {
            throw new IllegalArgumentException("not owner");
        }
        if (((LockCondition) c).core != this) {
            throw new IllegalArgumentException("Not owner");
        }
        checkOwner();
        return (LockCondition) c;
    }

    Thread owner() {
        return owner;
    }

    int ownerHolds() {
        return writeHolds;
    }

    int myWriteHolds() {
        return owner == Thread.currentThread() ? writeHolds : 0;
    }

    int readHolds() {
        return readHolds;
    }

    int myReadHolds() {
        int[] holds = holdsOf(Thread.currentThread());
        return holds == null ? 0 : holds[0];
    }

    String ownerText() {
        Thread t = owner;
        return t == null ? "[Unlocked]" : "[Locked by thread " + t.getName() + "]";
    }

    Collection<Thread> queued(boolean shared, boolean exclusive) {
        return turnstile.threads(shared, exclusive);
    }
}
