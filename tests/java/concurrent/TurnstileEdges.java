package concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Who may pass a queue, in which order it reads, and answers at the far ends of time. */
public class TurnstileEdges {
    interface Body { Object run() throws Throwable; }
    interface Step { void run() throws Throwable; }

    static final long HEAD_AGE_MS = 20;

    /** Runs on its own thread, so that a hang is one line of the transcript. */
    static void show(String label, Body body) throws Exception {
        String[] result = new String[1];
        Thread t = start(() -> {
            try { result[0] = " = " + body.run(); }
            catch (Throwable e) { result[0] = " ! " + e; }
        });
        t.join(20_000);
        System.out.println(label + (t.isAlive() ? " HANGS" : result[0]));
    }

    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static Thread named(String name, Step s) {
        Thread t = new Thread(() -> {
            try { s.run(); } catch (Throwable e) { System.out.println(name + " threw " + e); }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Waits until it blocks, in whichever way. */
    static void blocked(Thread t) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (;;) {
            Thread.State s = t.getState();
            if (s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING || s == Thread.State.BLOCKED) return;
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    public static void main(String[] args) throws Throwable {
        stuckHead();
        queueOrder();
        farTimeouts();
        freeWithOldHead();
        readersAndWriters();
        delays();
        System.exit(0);
    }

    /** A semaphore of 3 whose non-fair queue is headed by one who wants 5, long enough ago. */
    static Semaphore stuck(boolean fair) throws Exception {
        Semaphore s = new Semaphore(3, fair);
        blocked(named("head", () -> s.acquire(5)));
        Thread.sleep(HEAD_AGE_MS);
        return s;
    }

    static void stuckHead() throws Exception {
        Semaphore a = stuck(false);
        show("stuck head: acquire", () -> { a.acquire(); return a.availablePermits(); });
        Semaphore b = stuck(false);
        show("stuck head: acquire 3", () -> { b.acquire(3); return b.availablePermits(); });
        Semaphore c = stuck(false);
        show("stuck head: uninterruptibly", () -> { c.acquireUninterruptibly(); return c.availablePermits(); });
        Semaphore d = stuck(false);
        show("stuck head: acquire 0", () -> { d.acquire(0); return d.availablePermits(); });
        Semaphore e = stuck(false);
        show("stuck head: timed 0", () -> e.tryAcquire(0, TimeUnit.SECONDS) + " " + e.availablePermits());
        Semaphore f = stuck(false);
        show("stuck head: timed 200ms", () -> f.tryAcquire(200, TimeUnit.MILLISECONDS) + " " + f.availablePermits());
        Semaphore g = stuck(false);
        show("stuck head: more than free", () -> g.tryAcquire(4, 50, TimeUnit.MILLISECONDS) + " " + g.availablePermits());
        Semaphore h = stuck(true);
        show("stuck head, fair: timed", () -> h.tryAcquire(50, TimeUnit.MILLISECONDS) + " " + h.availablePermits());
        show("stuck head, fair: plain", () -> h.tryAcquire() + " " + h.availablePermits());
        Semaphore p = new Semaphore(3);
        p.acquire(3);
        Thread big = named("big", () -> { p.acquire(5); p.release(5); });
        blocked(big);
        Thread.sleep(HEAD_AGE_MS);
        p.release();
        show("small job past a big one", () -> { p.acquire(); return "through"; });
        p.release(5);
        big.join(20_000);
        System.out.println("big job done " + !big.isAlive() + " " + p.availablePermits());
    }

    static class Lock extends ReentrantLock {
        Collection<Thread> queued() { return getQueuedThreads(); }
        Collection<Thread> waiting(Condition c) { return getWaitingThreads(c); }
    }

    static class Rw extends ReentrantReadWriteLock {
        Rw() { super(true); }
        Collection<Thread> queued() { return getQueuedThreads(); }
        Collection<Thread> readers() { return getQueuedReaderThreads(); }
        Collection<Thread> writers() { return getQueuedWriterThreads(); }
    }

    static class Permits extends Semaphore {
        Permits() { super(0); }
        Collection<Thread> queued() { return getQueuedThreads(); }
    }

    static String names(Collection<Thread> ts) {
        List<String> out = new ArrayList<>();
        for (Thread t : ts) out.add(t.getName());
        return out.toString();
    }

    static void queueOrder() throws Exception {
        Lock l = new Lock();
        Condition c = l.newCondition();
        for (String n : new String[] {"w0", "w1", "w2"}) {
            blocked(named(n, () -> { l.lock(); c.awaitUninterruptibly(); l.unlock(); }));
        }
        l.lock();
        System.out.println("wait list " + names(l.waiting(c)));
        for (String n : new String[] {"q0", "q1", "q2"}) {
            blocked(named(n, () -> { l.lock(); l.unlock(); }));
        }
        System.out.println("lock queue " + names(l.queued()));
        c.signalAll();
        System.out.println("after signalAll " + names(l.queued()));
        l.unlock();

        Permits s = new Permits();
        for (String n : new String[] {"s0", "s1", "s2"}) {
            blocked(named(n, () -> s.acquire()));
        }
        System.out.println("semaphore queue " + names(s.queued()));
        s.release(3);

        Rw rw = new Rw();
        rw.writeLock().lock();
        blocked(named("r0", () -> rw.readLock().lock()));
        blocked(named("x0", () -> rw.writeLock().lock()));
        blocked(named("r1", () -> rw.readLock().lock()));
        blocked(named("x1", () -> rw.writeLock().lock()));
        System.out.println("rw queue " + names(rw.queued()));
        System.out.println("rw readers " + names(rw.readers()));
        System.out.println("rw writers " + names(rw.writers()));
    }

    static void farTimeouts() throws Exception {
        ReentrantLock held = new ReentrantLock();
        blocked(named("holder", () -> { held.lock(); new CountDownLatch(1).await(); }));
        show("tryLock min nanos", () -> held.tryLock(Long.MIN_VALUE, TimeUnit.NANOSECONDS));
        show("tryLock most negative days", () -> held.tryLock(-Long.MAX_VALUE, TimeUnit.DAYS));
        ReentrantLock l = new ReentrantLock();
        Condition c = l.newCondition();
        show("await min", () -> { l.lock(); try { return c.await(Long.MIN_VALUE, TimeUnit.NANOSECONDS); } finally { l.unlock(); } });
        show("await most negative days", () -> { l.lock(); try { return c.await(-Long.MAX_VALUE, TimeUnit.DAYS); } finally { l.unlock(); } });
        show("awaitNanos min stays positive", () -> { l.lock(); try { return c.awaitNanos(Long.MIN_VALUE) > 0; } finally { l.unlock(); } });
        show("awaitUntil the earliest date", () -> { l.lock(); try { return c.awaitUntil(new Date(Long.MIN_VALUE)) + " " + l.isHeldByCurrentThread(); } finally { l.unlock(); } });
        show("awaitUntil before 1970", () -> { l.lock(); try { return c.awaitUntil(new Date(-1)); } finally { l.unlock(); } });
        Semaphore none = new Semaphore(0);
        show("tryAcquire min", () -> none.tryAcquire(Long.MIN_VALUE, TimeUnit.NANOSECONDS));
        CountDownLatch latch = new CountDownLatch(1);
        show("latch min", () -> latch.await(Long.MIN_VALUE, TimeUnit.NANOSECONDS));
        show("latch most negative days", () -> latch.await(-Long.MAX_VALUE, TimeUnit.DAYS));
        Phaser ph = new Phaser(1);
        show("phaser min", () -> {
            try { return ph.awaitAdvanceInterruptibly(0, Long.MIN_VALUE, TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) { return "timed out"; }
        });
    }

    /** Whether one of a few tries got past a head that was just woken: the head may always win a race. */
    static boolean evenOnce(Step setUpAndTry) throws Throwable {
        tries = false;
        for (int i = 0; i < 20 && !tries; i++) setUpAndTry.run();
        return tries;
    }

    static boolean tries;

    static void freeWithOldHead() throws Throwable {
        System.out.println("free, old head, timed 0: " + evenOnce(() -> {
            ReentrantLock l = new ReentrantLock();
            l.lock();
            Thread q = named("q", () -> { l.lock(); l.unlock(); });
            blocked(q);
            Thread.sleep(HEAD_AGE_MS);
            l.unlock();
            if (l.tryLock(0, TimeUnit.SECONDS)) { tries = true; l.unlock(); }
            q.join();
        }));
        System.out.println("free, old head, timed 1 ns: " + evenOnce(() -> {
            ReentrantLock l = new ReentrantLock();
            l.lock();
            Thread q = named("q", () -> { l.lock(); l.unlock(); });
            blocked(q);
            Thread.sleep(HEAD_AGE_MS);
            l.unlock();
            if (l.tryLock(1, TimeUnit.NANOSECONDS)) { tries = true; l.unlock(); }
            q.join();
        }));
        ReentrantLock fair = new ReentrantLock(true);
        fair.lock();
        Thread fq = named("q", () -> { fair.lock(); fair.unlock(); });
        blocked(fq);
        Thread other = named("other", () -> System.out.println("fair, queued head, timed 0: " + fair.tryLock(0, TimeUnit.SECONDS)));
        other.join();
        fair.unlock();
        fq.join();
        System.out.println("write, free, old writer head: " + evenOnce(() -> {
            ReentrantReadWriteLock l = new ReentrantReadWriteLock();
            l.writeLock().lock();
            Thread q = named("q", () -> { l.writeLock().lock(); l.writeLock().unlock(); });
            blocked(q);
            Thread.sleep(HEAD_AGE_MS);
            l.writeLock().unlock();
            if (l.writeLock().tryLock(0, TimeUnit.SECONDS)) { tries = true; l.writeLock().unlock(); }
            q.join();
        }));
        System.out.println("read, free, old reader head: " + evenOnce(() -> {
            ReentrantReadWriteLock l = new ReentrantReadWriteLock();
            l.writeLock().lock();
            Thread q = named("q", () -> { l.readLock().lock(); l.readLock().unlock(); });
            blocked(q);
            Thread.sleep(HEAD_AGE_MS);
            l.writeLock().unlock();
            if (l.readLock().tryLock(0, TimeUnit.SECONDS)) { tries = true; l.readLock().unlock(); }
            q.join();
        }));
        System.out.println("semaphore, free, old head, timed 0: " + evenOnce(() -> {
            Semaphore s = new Semaphore(0);
            Thread q = named("q", () -> { s.acquireUninterruptibly(); s.release(); });
            blocked(q);
            Thread.sleep(HEAD_AGE_MS);
            s.release();
            if (s.tryAcquire(0, TimeUnit.SECONDS)) { tries = true; s.release(); }
            q.join();
        }));
    }

    static void readersAndWriters() throws Exception {
        int passed = 0;
        for (int i = 0; i < 100; i++) {
            ReentrantReadWriteLock l = new ReentrantReadWriteLock();
            l.readLock().lock();
            Thread w = named("w", () -> { l.writeLock().lock(); l.writeLock().unlock(); });
            while (!l.hasQueuedThreads()) Thread.yield();
            boolean[] got = new boolean[1];
            Thread r = named("r", () -> {
                got[0] = l.readLock().tryLock(0, TimeUnit.SECONDS);
                if (got[0]) l.readLock().unlock();
            });
            r.join();
            if (got[0]) passed++;
            l.readLock().unlock();
            w.join();
        }
        System.out.println("timed read past a young writer waiting out readers: " + passed);
    }

    /** Far off, and counting how often it is asked. */
    static class Counted implements Delayed {
        volatile int looks;
        public long getDelay(TimeUnit u) {
            looks++;
            return u.convert(1, TimeUnit.HOURS);
        }
        public int compareTo(Delayed o) { return 0; }
    }

    static class Due implements Delayed {
        public long getDelay(TimeUnit u) { return 0; }
        public int compareTo(Delayed o) { return 0; }
    }

    /** Sorts before anything, and throws the first time it is asked while armed. */
    static class Bomb implements Delayed {
        boolean armed = true;
        public long getDelay(TimeUnit u) {
            if (armed) {
                armed = false;
                throw new IllegalStateException("look");
            }
            return u.convert(1, TimeUnit.HOURS);
        }
        public int compareTo(Delayed o) { return -1; }
    }

    static Thread taker(DelayQueue<Delayed> q, String[] out, int at) {
        return start(() -> {
            try { q.take(); out[at] = "took"; }
            catch (Throwable e) { out[at] = e.getClass().getSimpleName(); }
        });
    }

    static void delays() throws Exception {
        String[] out = new String[2];
        DelayQueue<Delayed> dq = new DelayQueue<>();
        Due due = new Due();
        dq.add(due);
        System.out.println("take ready " + (dq.take() == due));
        Counted c = new Counted();
        dq.add(c);
        System.out.println("poll " + dq.poll() + " looks " + c.looks);
        c.looks = 0;
        System.out.println("poll 0 " + dq.poll(0, TimeUnit.SECONDS) + " looks " + c.looks);
        c.looks = 0;
        Thread t = taker(dq, out, 0);
        blocked(t);
        System.out.println("asleep on a far head: looks " + c.looks);
        Counted behind = new Counted();
        dq.add(behind);
        Thread.sleep(HEAD_AGE_MS);
        System.out.println("one added behind the head: looks " + c.looks + " " + behind.looks);
        dq.remove(behind);
        Thread second = taker(dq, out, 1);
        blocked(second);
        Thread.sleep(HEAD_AGE_MS);
        System.out.println("a second taker looks too: " + c.looks + " " + second.getState());
        t.interrupt();
        t.join();
        System.out.println("first, interrupted: " + out[0]);
        blocked(second);
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); second.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end; ) Thread.sleep(1);
        System.out.println("the second sleeps as head: looks " + c.looks + " " + second.getState());
        second.interrupt();
        second.join();

        DelayQueue<Delayed> empty = new DelayQueue<>();
        Thread e = taker(empty, out, 0);
        blocked(e);
        Counted first = new Counted();
        empty.add(first);
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); e.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end; ) Thread.sleep(1);
        System.out.println("added under a taker: looks " + first.looks + " " + e.getState());
        e.interrupt();
        e.join();

        DelayQueue<Delayed> bad = new DelayQueue<>();
        bad.add(new Counted());
        Thread dying = taker(bad, out, 0);
        for (long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); dying.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end; ) Thread.sleep(1);
        Thread next = taker(bad, out, 1);
        blocked(next);
        // A new head wakes the sleeper, whose look at it throws.
        bad.add(new Bomb());
        dying.join();
        System.out.println("look throws after a sleep: " + out[0]);
        bad.clear();
        bad.add(new Due());
        next.join(20_000);
        System.out.println("the next taker still goes: " + (next.isAlive() ? "HANGS" : out[1]));
    }
}
