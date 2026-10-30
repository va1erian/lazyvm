package concurrent;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** ReentrantLock and its Condition: state queries, toString, misuse, interrupts, fair order. */
public class Locks {
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

    /** Exposes the protected queries. */
    static class Open extends ReentrantLock {
        Open() { }
        Open(boolean fair) { super(fair); }
        Thread owner() { return getOwner(); }
        List<String> queued() {
            List<String> names = new ArrayList<>();
            for (Thread t : getQueuedThreads()) names.add(t.getName());
            java.util.Collections.sort(names);
            return names;
        }
        List<String> waiting(Condition c) {
            List<String> names = new ArrayList<>();
            for (Thread t : getWaitingThreads(c)) names.add(t.getName());
            java.util.Collections.sort(names);
            return names;
        }
    }

    static void states() throws Exception {
        Open l = new Open();
        show("new toString", () -> l);
        show("isFair", l::isFair);
        show("fair isFair", () -> new ReentrantLock(true).isFair());
        show("fair toString", () -> new ReentrantLock(true));
        show("isLocked", l::isLocked);
        show("holdCount", l::getHoldCount);
        show("owner", l::owner);
        l.lock();
        l.lock();
        show("locked toString", () -> l);
        show("holdCount 2", l::getHoldCount);
        show("isHeld", l::isHeldByCurrentThread);
        show("owner name", () -> l.owner().getName());
        l.unlock();
        show("holdCount 1", l::getHoldCount);
        show("still locked", l::isLocked);
        l.unlock();
        show("unlocked", l::isLocked);
        show("unlock free", () -> { l.unlock(); return "no"; });
        show("tryLock", l::tryLock);
        show("tryLock again", l::tryLock);
        show("holdCount tried", l::getHoldCount);
        l.unlock();
        l.unlock();
        show("hasQueuedThread null", () -> l.hasQueuedThread(null));
        show("tryLock null unit", () -> l.tryLock(1, null));
        show("tryLock zero", () -> { boolean b = l.tryLock(0, TimeUnit.SECONDS); if (b) l.unlock(); return b; });
    }

    static void contention() throws Exception {
        Open l = new Open();
        l.lock();
        boolean[] got = new boolean[3];
        Thread other = new Thread(() -> {
            got[0] = l.tryLock();
            try { got[1] = l.tryLock(20, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { got[1] = true; }
            try { l.unlock(); got[2] = true; } catch (IllegalMonitorStateException e) { got[2] = false; }
        }, "other");
        other.start();
        other.join();
        show("other tryLock", () -> got[0]);
        show("other timed tryLock", () -> got[1]);
        show("other unlock", () -> got[2]);
        show("other holdCount", () -> {
            int[] c = {-1};
            Thread t = new Thread(() -> c[0] = l.getHoldCount());
            t.start(); t.join();
            return c[0];
        });
        Thread a = new Thread(() -> { l.lock(); l.unlock(); }, "a");
        Thread b = new Thread(() -> { l.lock(); l.unlock(); }, "b");
        a.start();
        until(() -> l.getQueueLength() == 1);
        b.start();
        until(() -> l.getQueueLength() == 2);
        show("queueLength", l::getQueueLength);
        show("hasQueued", l::hasQueuedThreads);
        show("hasQueuedThread a", () -> l.hasQueuedThread(a));
        show("hasQueuedThread main", () -> l.hasQueuedThread(Thread.currentThread()));
        show("queued", l::queued);
        until(() -> a.getState() == Thread.State.WAITING);
        show("blocked state", a::getState);
        l.unlock();
        a.join();
        b.join();
        show("queue drained", l::getQueueLength);
    }

    static void fairOrder() throws Exception {
        ReentrantLock l = new ReentrantLock(true);
        List<String> order = new ArrayList<>();
        l.lock();
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            String name = "t" + i;
            ts[i] = new Thread(() -> { l.lock(); order.add(name); l.unlock(); }, name);
            ts[i].start();
            int want = i + 1;
            until(() -> l.getQueueLength() == want);
        }
        show("fair tryLock while queued", () -> {
            boolean[] r = new boolean[1];
            Thread t = new Thread(() -> { r[0] = l.tryLock(); if (r[0]) l.unlock(); });
            t.start(); t.join();
            return r[0];
        });
        l.unlock();
        for (Thread t : ts) t.join();
        show("fair order", () -> order);
    }

    static void interrupts() throws Exception {
        ReentrantLock l = new ReentrantLock();
        Thread me = Thread.currentThread();
        me.interrupt();
        show("lockInterruptibly flagged", () -> { l.lockInterruptibly(); return "no"; });
        show("flag cleared", Thread::interrupted);
        me.interrupt();
        show("timed tryLock flagged", () -> l.tryLock(1, TimeUnit.SECONDS));
        show("flag cleared 2", Thread::interrupted);
        me.interrupt();
        l.lock();
        show("lock keeps flag", me::isInterrupted);
        show("tryLock keeps flag", l::tryLock);
        l.unlock();
        l.unlock();
        Thread.interrupted();

        l.lock();
        String[] seen = new String[2];
        Thread blocked = new Thread(() -> {
            try { l.lockInterruptibly(); seen[0] = "acquired"; }
            catch (InterruptedException e) { seen[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        blocked.start();
        until(() -> l.getQueueLength() == 1);
        blocked.interrupt();
        blocked.join();
        show("interrupted while queued", () -> seen[0]);
        show("left queue", l::getQueueLength);
        Thread plain = new Thread(() -> {
            l.lock();
            seen[1] = "flag=" + Thread.currentThread().isInterrupted();
            l.unlock();
        });
        plain.start();
        until(() -> l.getQueueLength() == 1);
        plain.interrupt();
        Thread.sleep(10);
        show("uninterruptible still queued", l::getQueueLength);
        l.unlock();
        plain.join();
        show("lock after interrupt", () -> seen[1]);
    }

    static void conditionMisuse() throws Exception {
        Open l = new Open();
        Condition c = l.newCondition();
        Open other = new Open();
        Condition foreign = other.newCondition();
        show("await unowned", () -> { c.await(); return "no"; });
        show("awaitNanos unowned", () -> c.awaitNanos(1));
        show("await time unowned", () -> c.await(1, TimeUnit.MILLISECONDS));
        show("awaitUntil unowned", () -> c.awaitUntil(new Date()));
        show("awaitUninterruptibly unowned", () -> { c.awaitUninterruptibly(); return "no"; });
        show("signal unowned", () -> { c.signal(); return "no"; });
        show("signalAll unowned", () -> { c.signalAll(); return "no"; });
        show("hasWaiters unowned", () -> l.hasWaiters(c));
        show("waitQueueLength unowned", () -> l.getWaitQueueLength(c));
        show("waitingThreads unowned", () -> l.waiting(c));
        l.lock();
        show("hasWaiters null", () -> l.hasWaiters(null));
        show("waitQueueLength null", () -> l.getWaitQueueLength(null));
        show("waitingThreads null", () -> l.waiting(null));
        show("hasWaiters foreign", () -> l.hasWaiters(foreign));
        show("waitQueueLength foreign", () -> l.getWaitQueueLength(foreign));
        show("hasWaiters none", () -> l.hasWaiters(c));
        show("waitQueueLength none", () -> l.getWaitQueueLength(c));
        show("signal none", () -> { c.signal(); return "ok"; });
        show("awaitUntil null", () -> c.awaitUntil(null));
        show("await null unit", () -> c.await(1, null));
        show("awaitNanos negative", () -> c.awaitNanos(-5) <= 0);
        show("awaitNanos zero", () -> c.awaitNanos(0) <= 0);
        show("await timed out", () -> c.await(5, TimeUnit.MILLISECONDS));
        show("await zero", () -> c.await(0, TimeUnit.SECONDS));
        show("awaitUntil past", () -> c.awaitUntil(new Date(0)));
        show("held after waits", l::getHoldCount);
        l.unlock();
    }

    static void conditionFlow() throws Exception {
        ReentrantLock l = new ReentrantLock(true);
        Condition c = l.newCondition();
        Open probe = new Open();
        List<String> order = new ArrayList<>();
        Thread[] ts = new Thread[3];
        for (int i = 0; i < ts.length; i++) {
            String name = "w" + i;
            ts[i] = new Thread(() -> {
                l.lock();
                try {
                    l.lock();
                    c.await();
                    order.add(name + " holds " + l.getHoldCount());
                    l.unlock();
                } catch (InterruptedException e) {
                    order.add(name + " interrupted");
                } finally {
                    l.unlock();
                }
            }, name);
            ts[i].start();
            int want = i + 1;
            until(() -> {
                l.lock();
                try { return l.getWaitQueueLength(c) == want; } finally { l.unlock(); }
            });
        }
        l.lock();
        show("waiters", () -> l.getWaitQueueLength(c));
        show("hasWaiters", () -> l.hasWaiters(c));
        c.signal();
        show("waiters after signal", () -> l.getWaitQueueLength(c));
        show("queued after signal", l::getQueueLength);
        l.unlock();
        ts[0].join();
        show("first signalled", () -> order);
        l.lock();
        c.signalAll();
        show("waiters after signalAll", () -> l.getWaitQueueLength(c));
        l.unlock();
        ts[1].join();
        ts[2].join();
        show("signal FIFO", () -> order);
        probe.lock();
        Condition pc = probe.newCondition();
        Thread w = new Thread(() -> {
            probe.lock();
            try { pc.awaitUninterruptibly(); } finally { probe.unlock(); }
        }, "waiter");
        w.start();
        probe.unlock();
        until(() -> {
            probe.lock();
            try { return probe.hasWaiters(pc); } finally { probe.unlock(); }
        });
        until(() -> w.getState() == Thread.State.WAITING);
        probe.lock();
        show("waiting names", () -> probe.waiting(pc));
        show("waiter state", w::getState);
        pc.signal();
        probe.unlock();
        w.join();
    }

    static void conditionInterrupts() throws Exception {
        ReentrantLock l = new ReentrantLock();
        Condition c = l.newCondition();
        String[] seen = new String[3];
        Thread before = new Thread(() -> {
            l.lock();
            try { c.await(); seen[0] = "returned"; }
            catch (InterruptedException e) {
                seen[0] = n(e) + " held=" + l.isHeldByCurrentThread()
                    + " flag=" + Thread.currentThread().isInterrupted();
            } finally { l.unlock(); }
        });
        before.start();
        until(() -> { l.lock(); try { return l.hasWaiters(c); } finally { l.unlock(); } });
        before.interrupt();
        before.join();
        show("interrupt before signal", () -> seen[0]);

        Thread after = new Thread(() -> {
            l.lock();
            try { c.await(); seen[1] = "returned flag=" + Thread.currentThread().isInterrupted(); }
            catch (InterruptedException e) { seen[1] = "threw " + n(e); }
            finally { l.unlock(); }
        });
        after.start();
        until(() -> { l.lock(); try { return l.hasWaiters(c); } finally { l.unlock(); } });
        l.lock();
        c.signal();
        after.interrupt();
        l.unlock();
        after.join();
        show("interrupt after signal", () -> seen[1]);

        Thread unint = new Thread(() -> {
            l.lock();
            try { c.awaitUninterruptibly(); seen[2] = "returned flag=" + Thread.currentThread().isInterrupted(); }
            finally { l.unlock(); }
        });
        unint.start();
        until(() -> { l.lock(); try { return l.hasWaiters(c); } finally { l.unlock(); } });
        unint.interrupt();
        Thread.sleep(10);
        l.lock();
        show("uninterruptible still waiting", () -> l.hasWaiters(c));
        c.signal();
        l.unlock();
        unint.join();
        show("awaitUninterruptibly", () -> seen[2]);

        l.lock();
        Thread.currentThread().interrupt();
        show("await flagged", () -> { c.await(); return "no"; });
        Thread.currentThread().interrupt();
        show("awaitNanos flagged", () -> c.awaitNanos(1000));
        show("held", l::isHeldByCurrentThread);
        l.unlock();
    }

    static void asLock() {
        Lock l = new ReentrantLock();
        show("Lock iface newCondition class", () -> l.newCondition() != null);
        l.lock();
        l.unlock();
    }

    public static void main(String[] args) throws Exception {
        states();
        contention();
        fairOrder();
        interrupts();
        conditionMisuse();
        conditionFlow();
        conditionInterrupts();
        asLock();
    }
}
