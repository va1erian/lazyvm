package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Small contention runs: exclusion, permit conservation, no lost or repeated element. Totals only. */
public class Contention {
    static final int THREADS = 3, OPS = 2000;

    interface Body { void run(int thread) throws Exception; }

    /** Runs `body` on THREADS threads released together, and waits for them. */
    static void race(Body body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Thread[] ts = new Thread[THREADS];
        Throwable[] failed = new Throwable[1];
        for (int i = 0; i < THREADS; i++) {
            int id = i;
            ts[i] = new Thread(() -> {
                try { start.await(); body.run(id); }
                catch (Throwable t) { synchronized (failed) { failed[0] = t; } }
            });
            ts[i].start();
        }
        start.countDown();
        for (Thread t : ts) t.join();
        if (failed[0] != null) throw new AssertionError(failed[0]);
    }

    static void print(String label, Object value) { System.out.println(label + " = " + value); }

    static int plain;
    static int other;

    static void locks() throws Exception {
        ReentrantLock lock = new ReentrantLock();
        plain = 0;
        race(id -> {
            for (int i = 0; i < OPS; i++) {
                lock.lock();
                try { plain++; } finally { lock.unlock(); }
            }
        });
        print("ReentrantLock total", plain);
        print("ReentrantLock free", lock.isLocked() + " " + lock.getQueueLength());

        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        plain = 0;
        other = 0;
        AtomicInteger torn = new AtomicInteger();
        race(id -> {
            for (int i = 0; i < OPS; i++) {
                if (i % 4 == 3) {
                    rw.readLock().lock();
                    try { if (plain != other) torn.incrementAndGet(); } finally { rw.readLock().unlock(); }
                } else {
                    rw.writeLock().lock();
                    try { plain++; other++; } finally { rw.writeLock().unlock(); }
                }
            }
        });
        print("write lock total", plain + " " + other);
        print("readers saw a half write", torn.get());
        print("read-write lock free", rw.isWriteLocked() + " " + rw.getReadLockCount());
    }

    static void semaphore() throws Exception {
        Semaphore s = new Semaphore(2);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger over = new AtomicInteger();
        race(id -> {
            for (int i = 0; i < OPS; i++) {
                s.acquire();
                if (inside.incrementAndGet() > 2) over.incrementAndGet();
                inside.decrementAndGet();
                s.release();
            }
        });
        print("Semaphore over limit", over.get());
        print("Semaphore permits", s.availablePermits());
    }

    /** THREADS producers and THREADS consumers, each consumer taking OPS elements. */
    static void queue(String name, BlockingQueue<Integer> q, boolean fifo) throws Exception {
        int[][] taken = new int[THREADS][OPS];
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        Throwable[] failed = new Throwable[1];
        for (int t = 0; t < THREADS; t++) {
            int id = t;
            ts.add(new Thread(() -> {
                try { start.await(); for (int i = 0; i < OPS; i++) q.put(id * OPS + i); }
                catch (Throwable e) { synchronized (failed) { failed[0] = e; } }
            }));
            ts.add(new Thread(() -> {
                try { start.await(); for (int i = 0; i < OPS; i++) taken[id][i] = q.take(); }
                catch (Throwable e) { synchronized (failed) { failed[0] = e; } }
            }));
        }
        for (Thread t : ts) t.start();
        start.countDown();
        for (Thread t : ts) t.join();
        if (failed[0] != null) throw new AssertionError(failed[0]);
        boolean[] seen = new boolean[THREADS * OPS];
        long sum = 0;
        int repeats = 0, disorder = 0;
        for (int[] consumer : taken) {
            int[] last = {-1, -1, -1};
            for (int v : consumer) {
                sum += v;
                if (seen[v]) repeats++;
                seen[v] = true;
                int producer = v / OPS;
                if (v < last[producer]) disorder++;
                last[producer] = v;
            }
        }
        print(name + " sum", sum);
        print(name + " repeats", repeats);
        print(name + " left", q.size());
        if (fifo) print(name + " producer order broken", disorder);
    }

    static void skipList() throws Exception {
        ConcurrentSkipListMap<Integer, Integer> m = new ConcurrentSkipListMap<>();
        AtomicInteger inserted = new AtomicInteger(), removed = new AtomicInteger();
        race(id -> {
            for (int i = 0; i < OPS; i++) {
                if (m.putIfAbsent(i % 500, 0) == null) inserted.incrementAndGet();
                m.merge(1000 + i % 16, 1, Integer::sum);
            }
        });
        print("skip list inserted once", inserted.get());
        int total = 0;
        for (int i = 0; i < 16; i++) total += m.get(1000 + i);
        print("skip list merge total", total);
        print("skip list size", m.size());
        int previous = -1, disorder = 0;
        for (int k : m.keySet()) {
            if (k <= previous) disorder++;
            previous = k;
        }
        print("skip list out of order", disorder);
        race(id -> {
            for (int i = 0; i < 500; i++) if (m.remove(i) != null) removed.incrementAndGet();
        });
        print("skip list removed once", removed.get());
        print("skip list left", m.size());
    }

    /** Fewer values than the others: each addIfAbsent scans the list, so the work grows with its square. */
    static void copyOnWrite() throws Exception {
        CopyOnWriteArrayList<Integer> l = new CopyOnWriteArrayList<>();
        AtomicInteger added = new AtomicInteger();
        int values = 300;
        race(id -> {
            for (int i = 0; i < values; i++) if (l.addIfAbsent(i)) added.incrementAndGet();
        });
        print("addIfAbsent added once", added.get());
        print("addIfAbsent size", l.size());
        print("addIfAbsent distinct", new java.util.HashSet<>(l).size());
        CopyOnWriteArrayList<Integer> a = new CopyOnWriteArrayList<>();
        race(id -> {
            for (int i = 0; i < values; i++) a.add(i);
        });
        print("add lost none", a.size());
    }

    public static void main(String[] args) throws Exception {
        locks();
        semaphore();
        queue("ArrayBlockingQueue", new ArrayBlockingQueue<>(16), true);
        queue("LinkedBlockingQueue", new LinkedBlockingQueue<>(16), true);
        queue("LinkedBlockingDeque", new LinkedBlockingDeque<>(16), true);
        queue("PriorityBlockingQueue", new PriorityBlockingQueue<>(), false);
        skipList();
        copyOnWrite();
    }
}
