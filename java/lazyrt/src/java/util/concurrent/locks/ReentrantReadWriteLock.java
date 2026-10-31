package java.util.concurrent.locks;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** A {@link LockCore} with both sides in use, limited to 65535 holds of each (Javadoc). */
public class ReentrantReadWriteLock implements ReadWriteLock, java.io.Serializable {
    final LockCore core;
    private final ReadLock readView;
    private final WriteLock writeView;

    public ReentrantReadWriteLock() {
        this(false);
    }

    public ReentrantReadWriteLock(boolean fair) {
        core = new LockCore(fair, 65535);
        readView = new ReadLock(this);
        writeView = new WriteLock(this);
    }

    public ReentrantReadWriteLock.WriteLock writeLock() {
        return writeView;
    }

    public ReentrantReadWriteLock.ReadLock readLock() {
        return readView;
    }

    public final boolean isFair() {
        return core.turnstile.fair;
    }

    protected Thread getOwner() {
        return core.owner();
    }

    public int getReadLockCount() {
        return core.readHolds();
    }

    public boolean isWriteLocked() {
        return core.owner() != null;
    }

    public boolean isWriteLockedByCurrentThread() {
        return core.owner() == Thread.currentThread();
    }

    public int getWriteHoldCount() {
        return core.myWriteHolds();
    }

    public int getReadHoldCount() {
        return core.myReadHolds();
    }

    protected Collection<Thread> getQueuedWriterThreads() {
        return core.queued(false, true);
    }

    protected Collection<Thread> getQueuedReaderThreads() {
        return core.queued(true, false);
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
        return super.toString() + "[Write locks = " + core.ownerHolds() + ", Read locks = " + core.readHolds() + "]";
    }

    public static class ReadLock implements Lock, java.io.Serializable {
        private final LockCore core;

        protected ReadLock(ReentrantReadWriteLock lock) {
            core = lock.core;
        }

        public void lock() {
            core.lock(true);
        }

        public void lockInterruptibly() throws InterruptedException {
            core.lockInterruptibly(true);
        }

        public boolean tryLock() {
            return core.tryLock(true);
        }

        public boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException {
            return core.tryLock(true, timeout, unit);
        }

        public void unlock() {
            core.unlockRead();
        }

        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }

        public String toString() {
            return super.toString() + "[Read locks = " + core.readHolds() + "]";
        }
    }

    public static class WriteLock implements Lock, java.io.Serializable {
        private final LockCore core;

        protected WriteLock(ReentrantReadWriteLock lock) {
            core = lock.core;
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

        public String toString() {
            return super.toString() + core.ownerText();
        }

        public boolean isHeldByCurrentThread() {
            return core.owner() == Thread.currentThread();
        }

        public int getHoldCount() {
            return core.myWriteHolds();
        }
    }
}
