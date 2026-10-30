package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** ReentrantReadWriteLock: counts, toString, downgrade, misuse, limits, fair order. */
public class ReadWriteLocks {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static boolean elsewhere(Cond c) throws Exception {
        boolean[] r = new boolean[1];
        Thread t = new Thread(() -> { try { r[0] = c.ok(); } catch (Exception e) { r[0] = false; } });
        t.start();
        t.join();
        return r[0];
    }

    static void states() {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock();
        ReentrantReadWriteLock.ReadLock r = l.readLock();
        ReentrantReadWriteLock.WriteLock w = l.writeLock();
        show("same read view", () -> r == l.readLock());
        show("same write view", () -> w == l.writeLock());
        show("new", () -> l);
        show("read new", () -> r);
        show("write new", () -> w);
        show("fair", () -> new ReentrantReadWriteLock(true).isFair());
        show("default fair", l::isFair);
        r.lock();
        r.lock();
        show("read toString", () -> l);
        show("read view", () -> r);
        show("readLockCount", l::getReadLockCount);
        show("readHoldCount", l::getReadHoldCount);
        show("isWriteLocked", l::isWriteLocked);
        show("write tryLock under read", w::tryLock);
        r.unlock();
        r.unlock();
        w.lock();
        w.lock();
        show("write toString", () -> l);
        show("write view", () -> w);
        show("writeHoldCount", l::getWriteHoldCount);
        show("view holdCount", w::getHoldCount);
        show("isWriteLocked 2", l::isWriteLocked);
        show("byCurrent", l::isWriteLockedByCurrentThread);
        show("view byCurrent", w::isHeldByCurrentThread);
        r.lock();
        show("downgrade toString", () -> l);
        w.unlock();
        w.unlock();
        show("after downgrade", () -> l);
        show("after downgrade write", l::isWriteLocked);
        r.unlock();
        show("released", () -> l);
    }

    static void misuse() throws Exception {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock();
        show("read unlock free", () -> { l.readLock().unlock(); return "no"; });
        show("write unlock free", () -> { l.writeLock().unlock(); return "no"; });
        show("read newCondition", () -> l.readLock().newCondition());
        Condition c = l.writeLock().newCondition();
        show("await unowned", () -> { c.await(); return "no"; });
        show("signal unowned", () -> { c.signal(); return "no"; });
        show("hasWaiters unowned", () -> l.hasWaiters(c));
        l.writeLock().lock();
        show("hasWaiters null", () -> l.hasWaiters(null));
        show("waitLength null", () -> l.getWaitQueueLength(null));
        show("hasWaiters foreign", () -> l.hasWaiters(new ReentrantReadWriteLock().writeLock().newCondition()));
        show("hasWaiters none", () -> l.hasWaiters(c));
        show("await timed", () -> c.await(5, TimeUnit.MILLISECONDS));
        l.writeLock().unlock();
        l.readLock().lock();
        show("upgrade tryLock", l.writeLock()::tryLock);
        show("upgrade timed", () -> l.writeLock().tryLock(5, TimeUnit.MILLISECONDS));
        l.readLock().unlock();
        l.readLock().lock();
        show("read unlock other", () -> elsewhere(() -> {
            try { l.readLock().unlock(); return true; } catch (IllegalMonitorStateException e) { return false; }
        }));
        show("readHold other", () -> elsewhere(() -> l.getReadHoldCount() == 0));
        l.readLock().unlock();
        Thread.currentThread().interrupt();
        show("read interruptibly flagged", () -> { l.readLock().lockInterruptibly(); return "no"; });
        Thread.currentThread().interrupt();
        show("write interruptibly flagged", () -> { l.writeLock().lockInterruptibly(); return "no"; });
        Thread.currentThread().interrupt();
        show("read timed flagged", () -> l.readLock().tryLock(1, TimeUnit.SECONDS));
        Thread.interrupted();
    }

    static void limits() {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock();
        show("read limit", () -> {
            int i = 0;
            try { for (; i < 70000; i++) l.readLock().lock(); return "none"; }
            catch (Error e) { return i + " " + e; }
        });
        show("read tryLock at limit", () -> {
            try { return l.readLock().tryLock(); } catch (Error e) { return e.toString(); }
        });
        show("readLockCount", l::getReadLockCount);
        while (l.getReadHoldCount() > 0) l.readLock().unlock();
        show("write limit", () -> {
            int i = 0;
            try { for (; i < 70000; i++) l.writeLock().lock(); return "none"; }
            catch (Error e) { return i + " " + e; }
        });
        show("write tryLock at limit", () -> {
            try { return l.writeLock().tryLock(); } catch (Error e) { return e.toString(); }
        });
        show("writeHold", l::getWriteHoldCount);
        while (l.getWriteHoldCount() > 0) l.writeLock().unlock();
    }

    static void sharing() throws Exception {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock();
        Lock r = l.readLock();
        Lock w = l.writeLock();
        r.lock();
        show("read shared", () -> elsewhere(() -> {
            boolean ok = r.tryLock();
            if (ok) r.unlock();
            return ok;
        }));
        show("write blocked", () -> elsewhere(() -> w.tryLock()));
        r.unlock();
        w.lock();
        show("read blocked", () -> elsewhere(() -> r.tryLock()));
        show("write excluded", () -> elsewhere(() -> w.tryLock()));
        show("read blocked timed", () -> elsewhere(() -> r.tryLock(5, TimeUnit.MILLISECONDS)));
        w.unlock();
    }

    /** A queued writer holds later readers back in fair mode; tryLock still barges. */
    static void fairOrder() throws Exception {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock(true);
        List<String> order = new ArrayList<>();
        Object mu = new Object();
        l.writeLock().lock();
        Thread[] ts = {
            new Thread(() -> { l.readLock().lock(); note(order, mu, "r0"); l.readLock().unlock(); }),
            new Thread(() -> { l.writeLock().lock(); note(order, mu, "w1"); l.writeLock().unlock(); }),
            new Thread(() -> { l.writeLock().lock(); note(order, mu, "w2"); l.writeLock().unlock(); }),
        };
        for (int i = 0; i < ts.length; i++) {
            ts[i].start();
            int want = i + 1;
            until(() -> l.getQueueLength() == want);
        }
        show("queue", l::getQueueLength);
        show("hasQueued", l::hasQueuedThreads);
        show("hasQueuedThread", () -> l.hasQueuedThread(ts[1]));
        show("toString queued", () -> l);
        l.writeLock().unlock();
        for (Thread t : ts) t.join();
        show("fair order", () -> order);

        l.readLock().lock();
        Thread writer = new Thread(() -> { l.writeLock().lock(); l.writeLock().unlock(); });
        writer.start();
        until(() -> l.getQueueLength() == 1);
        show("fair timed read behind writer", () -> elsewhere(() -> {
            boolean ok = l.readLock().tryLock(0, TimeUnit.SECONDS);
            if (ok) l.readLock().unlock();
            return ok;
        }));
        show("barging read tryLock", () -> elsewhere(() -> {
            boolean ok = l.readLock().tryLock();
            if (ok) l.readLock().unlock();
            return ok;
        }));
        l.readLock().unlock();
        writer.join();
    }

    /** Exposes the protected queries. */
    static class Open extends ReentrantReadWriteLock {
        static List<String> names(java.util.Collection<Thread> ts) {
            List<String> names = new ArrayList<>();
            for (Thread t : ts) names.add(t.getName());
            java.util.Collections.sort(names);
            return names;
        }
        String owner() { Thread t = getOwner(); return t == null ? null : t.getName(); }
        List<String> writers() { return names(getQueuedWriterThreads()); }
        List<String> readers() { return names(getQueuedReaderThreads()); }
        List<String> all() { return names(getQueuedThreads()); }
        List<String> waiting(Condition c) { return names(getWaitingThreads(c)); }
    }

    static void protectedQueries() throws Exception {
        Open l = new Open();
        show("owner free", l::owner);
        l.writeLock().lock();
        show("owner", l::owner);
        Thread r = new Thread(() -> { l.readLock().lock(); l.readLock().unlock(); }, "reader");
        Thread w = new Thread(() -> { l.writeLock().lock(); l.writeLock().unlock(); }, "writer");
        r.start();
        until(() -> l.getQueueLength() == 1);
        w.start();
        until(() -> l.getQueueLength() == 2);
        show("queued writers", l::writers);
        show("queued readers", l::readers);
        show("queued all", l::all);
        Condition c = l.writeLock().newCondition();
        show("waiting none", () -> l.waiting(c));
        show("waiting null", () -> l.waiting(null));
        l.writeLock().unlock();
        r.join();
        w.join();
        show("waiting unowned", () -> l.waiting(c));
        l.readLock().lock();
        show("owner under read", l::owner);
        l.readLock().unlock();
    }

    static void note(List<String> order, Object mu, String s) {
        synchronized (mu) { order.add(s); }
    }

    static void condition() throws Exception {
        ReentrantReadWriteLock l = new ReentrantReadWriteLock();
        Condition c = l.writeLock().newCondition();
        String[] seen = new String[1];
        Thread t = new Thread(() -> {
            l.writeLock().lock();
            l.writeLock().lock();
            try { c.await(); seen[0] = "holds " + l.getWriteHoldCount(); }
            catch (InterruptedException e) { seen[0] = "interrupted"; }
            l.writeLock().unlock();
            l.writeLock().unlock();
        });
        t.start();
        until(() -> {
            l.writeLock().lock();
            try { return l.hasWaiters(c); } finally { l.writeLock().unlock(); }
        });
        l.writeLock().lock();
        show("waitLength", () -> l.getWaitQueueLength(c));
        c.signal();
        l.writeLock().unlock();
        t.join();
        show("condition return", () -> seen[0]);
    }

    public static void main(String[] args) throws Exception {
        states();
        misuse();
        limits();
        sharing();
        fairOrder();
        condition();
        protectedQueries();
        ReadWriteLock rw = new ReentrantReadWriteLock();
        show("iface views differ", () -> rw.readLock() != rw.writeLock());
    }
}
