package java.util.concurrent;

/** A count on a private monitor: waiters use wait/notifyAll, and nothing else can touch that monitor. */
public class CountDownLatch {
    private final Object mu = new Object();
    private int count;

    public CountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    public void await() throws InterruptedException {
        // docs/design/concurrent.md G5: the flag counts even when the latch is open.
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (mu) {
            while (count > 0) {
                mu.wait();
            }
        }
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        synchronized (mu) {
            while (count > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(mu, left);
            }
            return true;
        }
    }

    public void countDown() {
        synchronized (mu) {
            if (count > 0 && --count == 0) {
                mu.notifyAll();
            }
        }
    }

    public long getCount() {
        return count;
    }

    public String toString() {
        return super.toString() + "[Count = " + count + "]";
    }
}
