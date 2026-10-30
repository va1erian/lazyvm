package java.util.concurrent.locks;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** The write side of a {@link LockCore}, with no reader ever: holds up to the int range. */
public class ReentrantLock implements Lock, java.io.Serializable {
    private final LockCore core;

    public ReentrantLock() {
        this(false);
    }

    public ReentrantLock(boolean fair) {
        core = new LockCore(fair, Integer.MAX_VALUE);
    }

    public void lock() {
        core.lock(false);
    }

    public void lockInterruptibly() throws InterruptedException {
        core.lockInterruptibly(false);
    }

    public boolean tryLock() {
        return core.tryLock(false);
    }

    public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
        return core.tryLock(false, timeout, unit);
    }

    public void unlock() {
        core.unlock();
    }

    public Condition newCondition() {
        return core.newCondition();
    }

    public int getHoldCount() {
        return core.myWriteHolds();
    }

    public boolean isHeldByCurrentThread() {
        return core.owner() == Thread.currentThread();
    }

    public boolean isLocked() {
        return core.owner() != null;
    }

    public final boolean isFair() {
        return core.turnstile.fair;
    }

    protected Thread getOwner() {
        return core.owner();
    }

    public final boolean hasQueuedThreads() {
        return core.turnstile.length() != 0;
    }

    public final boolean hasQueuedThread(Thread thread) {
        return core.turnstile.contains(thread);
    }

    public final int getQueueLength() {
        return core.turnstile.length();
    }

    protected Collection<Thread> getQueuedThreads() {
        return core.queued(true, true);
    }

    public boolean hasWaiters(Condition condition) {
        return core.checked(condition).length() != 0;
    }

    public int getWaitQueueLength(Condition condition) {
        return core.checked(condition).length();
    }

    protected Collection<Thread> getWaitingThreads(Condition condition) {
        return core.checked(condition).threads();
    }

    public String toString() {
        return super.toString() + core.ownerText();
    }
}
