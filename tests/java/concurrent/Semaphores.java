package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Semaphore: permits, limits, fairness, interrupts, protected members, toString. */
public class Semaphores {
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

    static class Open extends Semaphore {
        Open(int p, boolean f) { super(p, f); }
        void reduce(int r) { reducePermits(r); }
        List<String> queued() {
            List<String> names = new ArrayList<>();
            for (Thread t : getQueuedThreads()) names.add(t.getName());
            Collections.sort(names);
            return names;
        }
    }

    static void counts() throws Exception {
        Semaphore s = new Semaphore(3);
        show("toString", () -> s);
        show("available", s::availablePermits);
        show("default fair", s::isFair);
        show("fair flag", () -> new Semaphore(0, true).isFair());
        show("negative ok", () -> new Semaphore(-2));
        s.acquire();
        s.acquire(2);
        show("after acquires", s::availablePermits);
        show("toString 0", () -> s);
        show("tryAcquire none", s::tryAcquire);
        show("tryAcquire n none", () -> s.tryAcquire(2));
        show("timed none", () -> s.tryAcquire(1, TimeUnit.MILLISECONDS));
        show("timed n none", () -> s.tryAcquire(2, 1, TimeUnit.MILLISECONDS));
        show("timed zero", () -> s.tryAcquire(0, TimeUnit.SECONDS));
        s.release();
        s.release(2);
        s.release(5);
        show("release beyond initial", s::availablePermits);
        show("tryAcquire", s::tryAcquire);
        show("tryAcquire n", () -> s.tryAcquire(2));
        show("timed", () -> s.tryAcquire(1, TimeUnit.SECONDS));
        show("timed n", () -> s.tryAcquire(2, 1, TimeUnit.SECONDS));
        show("drain", s::drainPermits);
        show("drain empty", s::drainPermits);
        show("zero acquire", () -> { s.acquire(0); return "ok"; });
        show("tryAcquire zero", () -> s.tryAcquire(0));
        Semaphore neg = new Semaphore(-2);
        show("negative tryAcquire", neg::tryAcquire);
        show("negative drain", neg::drainPermits);
        show("drained negative", neg::availablePermits);
        s.release(0);
        show("after release 0", s::availablePermits);
    }

    static void arguments() {
        Semaphore s = new Semaphore(1);
        show("acquire -1", () -> { s.acquire(-1); return "no"; });
        show("uninterruptibly -1", () -> { s.acquireUninterruptibly(-1); return "no"; });
        show("tryAcquire -1", () -> s.tryAcquire(-1));
        show("timed -1", () -> s.tryAcquire(-1, 1, TimeUnit.SECONDS));
        show("release -1", () -> { s.release(-1); return "no"; });
        show("timed null unit", () -> s.tryAcquire(1, 1, null));
        show("timed null unit single", () -> s.tryAcquire(1, null));
        Open o = new Open(1, false);
        show("reduce -1", () -> { o.reduce(-1); return "no"; });
        o.reduce(3);
        show("reduced below zero", o::availablePermits);
        Semaphore max = new Semaphore(Integer.MAX_VALUE);
        show("release overflow", () -> { max.release(); return "no"; });
        Open min = new Open(Integer.MIN_VALUE, false);
        show("reduce underflow", () -> { min.reduce(1); return "no"; });
        show("from limits", () -> max.availablePermits() + " " + min.availablePermits());
    }

    static void waiting() throws Exception {
        Open s = new Open(0, false);
        show("no waiters", s::hasQueuedThreads);
        Thread a = new Thread(() -> { try { s.acquire(2); s.release(2); } catch (InterruptedException e) { } }, "a");
        a.start();
        until(s::hasQueuedThreads);
        show("queue length", s::getQueueLength);
        show("queued", s::queued);
        show("not enough", () -> { s.release(); return s.availablePermits(); });
        Thread.sleep(5);
        show("still queued", s::getQueueLength);
        s.release();
        a.join();
        show("back", s::availablePermits);
        s.drainPermits();
        String[] res = new String[2];
        Thread i = new Thread(() -> {
            try { s.acquire(); res[0] = "got"; }
            catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        i.start();
        until(s::hasQueuedThreads);
        i.interrupt();
        i.join();
        show("interrupted acquire", () -> res[0]);
        show("left queue", s::getQueueLength);
        Thread u = new Thread(() -> {
            s.acquireUninterruptibly();
            res[1] = "got flag=" + Thread.currentThread().isInterrupted();
        });
        u.start();
        until(s::hasQueuedThreads);
        u.interrupt();
        Thread.sleep(5);
        show("uninterruptible stays", s::getQueueLength);
        s.release();
        u.join();
        show("uninterruptible", () -> res[1]);
        s.release(5);
        Thread.currentThread().interrupt();
        show("acquire flagged, permits free", () -> { s.acquire(); return "got"; });
        Thread.currentThread().interrupt();
        show("timed flagged, permits free", () -> s.tryAcquire(0, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        show("tryAcquire flagged", s::tryAcquire);
        Thread.currentThread().interrupt();
        s.acquireUninterruptibly();
        show("uninterruptibly flagged", Thread::interrupted);
        show("permits", s::availablePermits);
    }

    static void fairness() throws Exception {
        Semaphore s = new Semaphore(0, true);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Thread[] ts = new Thread[4];
        for (int i = 0; i < ts.length; i++) {
            String name = "t" + i;
            int need = i == 1 ? 2 : 1;
            ts[i] = new Thread(() -> { s.acquireUninterruptibly(need); order.add(name); });
            ts[i].start();
            int want = i + 1;
            until(() -> s.getQueueLength() == want);
        }
        s.release();
        ts[0].join();
        show("head served", () -> order);
        s.release();
        Thread.sleep(5);
        show("head needs 2: those behind it wait", () -> order + " " + s.availablePermits() + " " + s.getQueueLength());
        s.release();
        ts[1].join();
        show("then", () -> order);
        s.release(2);
        ts[2].join();
        ts[3].join();
        show("fair order", () -> order);

        Semaphore b = new Semaphore(1, true);
        Thread big = new Thread(() -> b.acquireUninterruptibly(2));
        big.start();
        until(() -> b.getQueueLength() == 1);
        show("fair timed respects the queue", () -> b.tryAcquire(0, TimeUnit.SECONDS) + " " + b.availablePermits());
        show("fair tryAcquire barges", () -> b.tryAcquire() + " " + b.availablePermits());
        b.release(2);
        big.join();
    }

    public static void main(String[] args) throws Exception {
        counts();
        arguments();
        waiting();
        fairness();
    }
}
