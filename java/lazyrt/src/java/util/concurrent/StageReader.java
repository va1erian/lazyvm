package java.util.concurrent;

import java.util.concurrent.locks.LockSupport;

/**
 * A thread blocked in {@code get} or {@code join}. It waits on the stage's own list and not in a
 * {@code Turnstile}, because a reader is released at its place among the dependents and counts as
 * one (docs/design/concurrent.md, "Readers"). A {@code ManagedBlocker} so that a pool worker
 * blocked here is replaced.
 */
final class StageReader extends StageLink implements ForkJoinPool.ManagedBlocker {
    private final Thread thread = Thread.currentThread();
    private final boolean interruptible;
    private final boolean timed;
    private final long deadline;
    private volatile boolean released;
    /** Interrupted while waiting: the end of an interruptible wait, or a flag to set again. */
    boolean interrupted;

    StageReader(boolean interruptible, boolean timed, long deadline) {
        this.interruptible = interruptible;
        this.timed = timed;
        this.deadline = deadline;
    }

    @Override
    CompletableFuture<?> fire() {
        released = true;
        LockSupport.unpark(thread);
        return null;
    }

    @Override
    public boolean isReleasable() {
        return released || interruptible && interrupted || timed && deadline - System.nanoTime() <= 0;
    }

    @Override
    public boolean block() {
        if (!isReleasable()) {
            if (timed) {
                LockSupport.parkNanos(this, deadline - System.nanoTime());
            } else {
                LockSupport.park(this);
            }
            // Left set, the flag would make every park return at once.
            interrupted |= Thread.interrupted();
        }
        return isReleasable();
    }
}
