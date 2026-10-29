package thread;

/** Monitors: a bounded buffer with wait/notifyAll, reentrancy, and misuse. */
public class ProducerConsumer {
    static final int PRODUCERS = 3;
    static final int CONSUMERS = 2;
    static final int ITEMS = 2000;
    static final int STOP = -1;

    /** A bounded FIFO guarded by its own monitor. */
    static final class Buffer {
        private final int[] items = new int[8];
        private int head;
        private int count;
        int maxSeen;

        synchronized void put(int v) throws InterruptedException {
            while (count == items.length) {
                wait();
            }
            items[(head + count) % items.length] = v;
            count++;
            maxSeen = Math.max(maxSeen, count);
            notifyAll();
        }

        synchronized int take() throws InterruptedException {
            while (count == 0) {
                wait();
            }
            int v = items[head];
            head = (head + 1) % items.length;
            count--;
            notifyAll();
            return v;
        }
    }

    static int counter;

    static synchronized void bump() {
        counter++;
    }

    public static void main(String[] args) throws Exception {
        buffer();
        counters();
        reentrancy();
        misuse();
        exceptionReleases();
        notifyOne();
    }

    static void buffer() throws InterruptedException {
        final Buffer buf = new Buffer();
        final long[] consumed = new long[CONSUMERS];
        final int[] counts = new int[CONSUMERS];
        Thread[] producers = new Thread[PRODUCERS];
        Thread[] consumers = new Thread[CONSUMERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int base = p * ITEMS;
            producers[p] = new Thread(() -> {
                try {
                    for (int i = 1; i <= ITEMS; i++) {
                        buf.put(base + i);
                    }
                } catch (InterruptedException e) {
                    System.out.println("producer interrupted");
                }
            });
        }
        for (int c = 0; c < CONSUMERS; c++) {
            final int id = c;
            consumers[c] = new Thread(() -> {
                try {
                    for (int v = buf.take(); v != STOP; v = buf.take()) {
                        consumed[id] += v;
                        counts[id]++;
                    }
                } catch (InterruptedException e) {
                    System.out.println("consumer interrupted");
                }
            });
        }
        for (Thread t : consumers) {
            t.start();
        }
        for (Thread t : producers) {
            t.start();
        }
        for (Thread t : producers) {
            t.join();
        }
        for (int c = 0; c < CONSUMERS; c++) {
            buf.put(STOP);
        }
        for (Thread t : consumers) {
            t.join();
        }
        long total = 0;
        int n = 0;
        for (int c = 0; c < CONSUMERS; c++) {
            total += consumed[c];
            n += counts[c];
        }
        long expect = 0;
        for (int i = 1; i <= PRODUCERS * ITEMS; i++) {
            expect += i;
        }
        System.out.println("consumed " + n + " items, sum " + total + " (expected " + expect + ")");
        System.out.println("buffer never overfilled: " + (buf.maxSeen <= 8));
    }

    static void counters() throws InterruptedException {
        final Object lock = new Object();
        final int[] shared = new int[1];
        Thread[] ts = new Thread[8];
        for (int t = 0; t < ts.length; t++) {
            ts[t] = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    synchronized (lock) {
                        int v = shared[0];
                        if (i % 100 == 0) {
                            Thread.yield();
                        }
                        shared[0] = v + 1;
                    }
                    bump();
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        System.out.println("block counter " + shared[0] + ", static method counter " + counter);
    }

    static synchronized int depth(int n) {
        return n == 0 ? (Thread.holdsLock(ProducerConsumer.class) ? 1 : 0) : depth(n - 1) + 1;
    }

    static void reentrancy() throws InterruptedException {
        Object o = new Object();
        synchronized (o) {
            synchronized (o) {
                synchronized (o) {
                    System.out.println("nested holds: " + Thread.holdsLock(o));
                    o.wait(1);
                    System.out.println("after wait still nested: " + Thread.holdsLock(o));
                }
                System.out.println("two levels left: " + Thread.holdsLock(o));
            }
        }
        System.out.println("released: " + Thread.holdsLock(o));
        System.out.println("recursive synchronized method: " + depth(50));
        System.out.println("class lock after: " + Thread.holdsLock(ProducerConsumer.class));
    }

    static void misuse() throws InterruptedException {
        Object o = new Object();
        try {
            o.wait();
        } catch (IllegalMonitorStateException e) {
            System.out.println("wait: " + e);
        }
        try {
            o.wait(10);
        } catch (IllegalMonitorStateException e) {
            System.out.println("timed wait: " + e);
        }
        try {
            o.notify();
        } catch (IllegalMonitorStateException e) {
            System.out.println("notify: " + e);
        }
        try {
            o.notifyAll();
        } catch (IllegalMonitorStateException e) {
            System.out.println("notifyAll: " + e);
        }
        synchronized (o) {
            try {
                o.wait(-1);
            } catch (IllegalArgumentException e) {
                System.out.println("negative timeout: " + e.getMessage());
            }
        }
        try {
            Thread.holdsLock(null);
        } catch (NullPointerException e) {
            System.out.println("holdsLock(null): " + e);
        }
        Object nothing = null;
        try {
            synchronized (nothing) {
                System.out.println("locked null");
            }
        } catch (NullPointerException e) {
            System.out.println("synchronized (null): NullPointerException");
        }
        // Another thread's lock is not ours to wait on.
        final Object held = new Object();
        final Object ready = new Object();
        final boolean[] go = new boolean[2];
        Thread owner = new Thread(() -> {
            synchronized (held) {
                synchronized (ready) {
                    go[0] = true;
                    ready.notifyAll();
                    while (!go[1]) {
                        try {
                            ready.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        });
        owner.start();
        synchronized (ready) {
            while (!go[0]) {
                ready.wait();
            }
            try {
                held.notify();
            } catch (IllegalMonitorStateException e) {
                System.out.println("notify on a lock another thread holds: " + e);
            }
            go[1] = true;
            ready.notifyAll();
        }
        owner.join();
    }

    static void exceptionReleases() throws InterruptedException {
        final Object o = new Object();
        Thread t = new Thread(() -> {
            synchronized (o) {
                throw new RuntimeException("thrown while holding the lock");
            }
        });
        t.setUncaughtExceptionHandler((th, e) -> System.out.println("handler: " + e.getMessage()));
        t.start();
        t.join();
        synchronized (o) {
            System.out.println("lock free after the exception");
        }
        try {
            throwInSynchronizedMethod();
        } catch (IllegalStateException e) {
            System.out.println("caught " + e.getMessage() + ", class lock held: "
                    + Thread.holdsLock(ProducerConsumer.class));
        }
    }

    static synchronized void throwInSynchronizedMethod() {
        throw new IllegalStateException("from a synchronized method");
    }

    /** notify wakes exactly one waiter per call. */
    static void notifyOne() throws InterruptedException {
        final Object o = new Object();
        final int[] woken = new int[1];
        final int[] waiting = new int[1];
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                synchronized (o) {
                    waiting[0]++;
                    try {
                        o.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                    woken[0]++;
                }
            });
            ts[i].start();
        }
        for (int round = 1; round <= ts.length; round++) {
            while (true) {
                synchronized (o) {
                    if (waiting[0] == ts.length && woken[0] == round - 1) {
                        o.notify();
                        break;
                    }
                }
                Thread.yield();
            }
            while (true) {
                synchronized (o) {
                    if (woken[0] >= round) {
                        break;
                    }
                }
                Thread.yield();
            }
            System.out.println("after notify " + round + ": woken " + woken[0]);
        }
        for (Thread t : ts) {
            t.join();
        }
    }
}
