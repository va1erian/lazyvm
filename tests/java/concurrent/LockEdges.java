package concurrent;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Corners of the locks: argument check order, who wakes whom, read holds around await, the read limit. */
public class LockEdges {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    /** Prints the result and the interrupt flag it left, and clears the flag. */
    static void show(String label, Body body) {
        String text;
        try { text = " = " + n(body.run()); }
        catch (Throwable t) { text = " ! " + n(t); }
        System.out.println(label + text + " flag=" + Thread.interrupted());
    }

    static void flag() { Thread.currentThread().interrupt(); }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static class Foreign implements Condition {
        public void await() { }
        public void awaitUninterruptibly() { }
        public long awaitNanos(long n) { return 0; }
        public boolean await(long t, TimeUnit u) { return false; }
        public boolean awaitUntil(Date d) { return false; }
        public void signal() { }
        public void signalAll() { }
    }

    static void checkOrder() {
        ReentrantLock l = new ReentrantLock();
        Condition c = l.newCondition();
        flag(); show("await unowned flagged", () -> { c.await(); return "no"; });
        flag(); show("awaitNanos unowned flagged", () -> c.awaitNanos(5));
        flag(); show("await time unowned flagged", () -> c.await(1, TimeUnit.SECONDS));
        flag(); show("awaitUntil unowned flagged", () -> c.awaitUntil(new Date(0)));
        flag(); show("awaitUninterruptibly unowned flagged", () -> { c.awaitUninterruptibly(); return "no"; });
        show("awaitUntil null unowned", () -> c.awaitUntil(null));
        show("await null unit unowned", () -> c.await(1, null));
        flag(); show("awaitUntil null flagged", () -> c.awaitUntil(null));
        flag(); show("await null unit flagged", () -> c.await(1, null));
        show("hasWaiters custom condition", () -> l.hasWaiters(new Foreign()));
        show("hasWaiters foreign unowned", () -> l.hasWaiters(new ReentrantLock().newCondition()));
        show("hasWaiters null unowned", () -> l.hasWaiters(null));
        flag(); show("tryLock null unit flagged", () -> l.tryLock(1, null));
        flag(); show("tryLock zero flagged", () -> l.tryLock(0, TimeUnit.SECONDS));
        flag(); show("tryLock negative flagged", () -> l.tryLock(-1, TimeUnit.SECONDS));
        l.lock();
        flag(); show("await zero flagged", () -> c.await(0, TimeUnit.SECONDS));
        flag(); show("awaitNanos negative flagged", () -> c.awaitNanos(-1) <= 0);
        flag(); show("awaitUntil past flagged", () -> c.awaitUntil(new Date(0)));
        flag(); show("lockInterruptibly flagged reentrant", () -> { l.lockInterruptibly(); return "no"; });
        flag(); show("timed tryLock flagged reentrant", () -> l.tryLock(1, TimeUnit.SECONDS));
        show("holds after flagged reentry", l::getHoldCount);
        l.unlock();
    }

    static void conditionReturns() throws Exception {
        ReentrantLock l = new ReentrantLock();
        Condition c = l.newCondition();
        Runnable signaller = () -> { l.lock(); c.signalAll(); l.unlock(); };
        l.lock();
        show("awaitNanos runs out", () -> c.awaitNanos(2_000_000L) <= 0);
        show("await runs out", () -> c.await(2, TimeUnit.MILLISECONDS));
        show("awaitUntil runs out", () -> c.awaitUntil(new Date(System.currentTimeMillis() + 2)));
        Thread a = start(signaller);
        show("awaitNanos signalled", () -> c.awaitNanos(TimeUnit.SECONDS.toNanos(30)) > 0);
        a.join();
        Thread b = start(signaller);
        show("await signalled", () -> c.await(30, TimeUnit.SECONDS));
        b.join();
        Thread d = start(signaller);
        show("awaitUntil signalled", () -> c.awaitUntil(new Date(System.currentTimeMillis() + 30_000)));
        d.join();
        Thread e = start(signaller);
        show("await longest time", () -> c.await(Long.MAX_VALUE, TimeUnit.DAYS));
        e.join();
        Thread f = start(signaller);
        show("awaitNanos longest time", () -> c.awaitNanos(Long.MAX_VALUE) > 0);
        f.join();
        flag();
        Thread g = start(signaller);
        c.awaitUninterruptibly();
        show("awaitUninterruptibly keeps flag", () -> Thread.currentThread().isInterrupted() + " holds " + l.getHoldCount());
        g.join();
        l.unlock();

        // An owner that starts to wait lets the queue in: nobody else would wake it.
        l.lock();
        boolean[] got = new boolean[1];
        Thread queued = start(() -> { l.lock(); got[0] = true; c.signal(); l.unlock(); });
        until(() -> l.getQueueLength() == 1);
        show("owner await wakes the queue", () -> c.await(30, TimeUnit.SECONDS) + " " + got[0]);
        l.unlock();
        queued.join();
    }

    static void readHolds() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        rw.readLock().lock();
        rw.readLock().unlock();
        show("second read unlock", () -> { rw.readLock().unlock(); return "no"; });
        show("read count after", rw::getReadLockCount);
        rw.readLock().lock();
        show("read await only", () -> { rw.writeLock().newCondition().await(); return "no"; });
        rw.readLock().unlock();

        Condition c = rw.writeLock().newCondition();
        rw.readLock().lock();
        show("signal with read hold only", () -> { c.signal(); return "no"; });
        rw.readLock().unlock();
        rw.writeLock().lock();
        rw.readLock().lock();
        String[] during = new String[1];
        Thread sig = start(() -> {
            rw.writeLock().lock();
            during[0] = "read locks " + rw.getReadLockCount() + " write holds " + rw.getWriteHoldCount();
            c.signal();
            rw.writeLock().unlock();
        });
        until(() -> rw.getQueueLength() == 1);
        show("await with both holds", () -> c.await(30, TimeUnit.SECONDS));
        show("seen during that wait", () -> during[0]);
        show("both holds back", () -> rw.getReadHoldCount() + " " + rw.getWriteHoldCount() + " " + rw.getReadLockCount());
        rw.readLock().unlock();
        rw.writeLock().unlock();
        sig.join();
        show("both released", () -> rw);

        Thread holder = new Thread(() -> rw.writeLock().lock(), "holder");
        holder.start();
        holder.join();
        show("toString, held elsewhere", () -> rw + " " + rw.writeLock());
        show("my view of it", () -> rw.getWriteHoldCount() + " " + rw.writeLock().getHoldCount() + " " + rw.isWriteLocked());
    }

    static void fairReentry() throws Exception {
        ReentrantLock fl = new ReentrantLock(true);
        fl.lock();
        Thread q = start(() -> { fl.lock(); fl.unlock(); });
        until(() -> fl.getQueueLength() == 1);
        show("fair timed reentry behind queue", () -> { boolean b = fl.tryLock(0, TimeUnit.SECONDS); if (b) fl.unlock(); return b; });
        show("fair lock reentry behind queue", () -> { fl.lock(); int h = fl.getHoldCount(); fl.unlock(); return h; });
        fl.unlock();
        q.join();

        ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);
        rw.readLock().lock();
        Thread w = start(() -> { rw.writeLock().lock(); rw.writeLock().unlock(); });
        until(() -> rw.getQueueLength() == 1);
        show("fair read reentry timed", () -> { boolean b = rw.readLock().tryLock(0, TimeUnit.SECONDS); if (b) rw.readLock().unlock(); return b; });
        show("fair read reentry", () -> { rw.readLock().lock(); int h = rw.getReadHoldCount(); rw.readLock().unlock(); return h; });
        rw.readLock().unlock();
        w.join();
        rw.writeLock().lock();
        Thread r = start(() -> { rw.readLock().lock(); rw.readLock().unlock(); });
        until(() -> rw.getQueueLength() == 1);
        show("writer reads past its queue", () -> { boolean b = rw.readLock().tryLock(0, TimeUnit.SECONDS); if (b) rw.readLock().unlock(); return b; });
        rw.writeLock().unlock();
        r.join();
    }

    /** A fair queue binds a newcomer from the first moment: no sleep between the steps. */
    static void fairAtOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            ReentrantReadWriteLock rw = new ReentrantReadWriteLock(true);
            rw.readLock().lock();
            Thread w = start(() -> { rw.writeLock().lock(); rw.writeLock().unlock(); });
            while (rw.getQueueLength() == 0) { }
            boolean[] got = {true};
            Thread other = new Thread(() -> {
                try { got[0] = rw.readLock().tryLock(0, TimeUnit.SECONDS); if (got[0]) rw.readLock().unlock(); }
                catch (InterruptedException e) { }
            });
            other.start();
            other.join();
            show("fair read behind a new writer", () -> got[0]);
            rw.readLock().unlock();
            w.join();
        }
    }

    /** One writer lets every queued reader in, though it wakes only the first. */
    static void readerChain() throws Exception {
        for (boolean fair : new boolean[] {false, true}) {
            ReentrantReadWriteLock rw = new ReentrantReadWriteLock(fair);
            rw.writeLock().lock();
            CountDownLatch inside = new CountDownLatch(4);
            CountDownLatch out = new CountDownLatch(1);
            for (int i = 0; i < 4; i++) {
                start(() -> {
                    rw.readLock().lock();
                    inside.countDown();
                    try { out.await(); } catch (InterruptedException e) { }
                    rw.readLock().unlock();
                });
            }
            until(() -> rw.getQueueLength() == 4);
            rw.writeLock().unlock();
            show("all readers in, fair=" + fair, () -> inside.await(30, TimeUnit.SECONDS) + " " + rw.getReadLockCount());
            out.countDown();
        }
    }

    static void fill(ReentrantReadWriteLock l) {
        for (int i = 0; i < 65535; i++) l.readLock().lock();
    }

    /** A queued reader meets the read limit when its turn comes: it fails and the next is woken. */
    static void readLimitQueued() throws Exception {
        for (boolean fair : new boolean[] {false, true}) {
            ReentrantReadWriteLock l = new ReentrantReadWriteLock(fair);
            l.writeLock().lock();
            fill(l);
            String[] r = new String[3];
            Thread a = start(() -> {
                try { l.readLock().lock(); r[0] = "got"; } catch (Error e) { r[0] = e.toString(); }
            });
            until(() -> l.getQueueLength() == 1);
            Thread b = start(() -> {
                try { l.readLock().lockInterruptibly(); r[1] = "got"; }
                catch (Error | InterruptedException e) { r[1] = e.toString(); }
            });
            until(() -> l.getQueueLength() == 2);
            Thread c = start(() -> {
                try { r[2] = "got " + l.readLock().tryLock(30, TimeUnit.SECONDS); }
                catch (Error | InterruptedException e) { r[2] = e.toString(); }
            });
            until(() -> l.getQueueLength() == 3);
            l.writeLock().unlock();
            a.join();
            b.join();
            c.join();
            show("queued lock at the limit, fair=" + fair, () -> r[0]);
            show("queued interruptibly at the limit", () -> r[1]);
            show("queued timed at the limit", () -> r[2]);
            show("after them", () -> l.getReadLockCount() + " " + l.getQueueLength() + " " + l);
        }
    }

    static void support() throws Exception {
        LockSupport.unpark(Thread.currentThread());
        flag();
        LockSupport.park();
        Thread.interrupted();
        long t0 = System.nanoTime();
        LockSupport.parkNanos(30_000_000L);
        show("flagged park used the permit", () -> System.nanoTime() - t0 >= 15_000_000L);
        long t1 = System.nanoTime();
        LockSupport.parkNanos("b", 0);
        LockSupport.parkNanos("b", -1);
        LockSupport.parkUntil("b", 0);
        show("blocker forms return at once", () -> System.nanoTime() - t1 < TimeUnit.SECONDS.toNanos(5));
        show("blocker not left behind", () -> LockSupport.getBlocker(Thread.currentThread()));
        Thread t = new Thread(() -> LockSupport.park());
        t.setDaemon(true);
        LockSupport.unpark(t);
        t.start();
        t.join(50);
        show("unpark before start does nothing", t::isAlive);
        LockSupport.unpark(t);
        t.join();
    }

    public static void main(String[] args) throws Exception {
        checkOrder();
        conditionReturns();
        readHolds();
        fairReentry();
        fairAtOnce();
        readerChain();
        readLimitQueued();
        support();
    }
}
