package concurrent;

import java.util.concurrent.*;

/** Corners of the synchronizers: check order, a running barrier action, wake chains, wide random ranges. */
public class SynchronizerEdges {
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

    /** Runs `body` on a thread and returns once it is parked; `result[0]` has what it ended with. */
    static Thread blocked(String[] result, Body body) throws Exception {
        Thread t = start(() -> {
            try { result[0] = "= " + n(body.run()); }
            catch (Throwable e) { result[0] = "! " + n(e); }
        });
        until(() -> t.getState() == Thread.State.WAITING);
        return t;
    }

    static void latches() {
        CountDownLatch c = new CountDownLatch(1);
        flag(); show("latch null unit flagged", () -> c.await(1, null));
        flag(); show("latch zero flagged", () -> c.await(0, TimeUnit.SECONDS));
        flag(); show("latch countDown flagged", () -> { c.countDown(); return c.getCount(); });
        flag(); show("latch open, timed zero flagged", () -> c.await(0, TimeUnit.SECONDS));
    }

    static void exchangers() throws Exception {
        Exchanger<String> x = new Exchanger<>();
        flag(); show("exchange zero flagged", () -> x.exchange("a", 0, TimeUnit.SECONDS));
        flag(); show("exchange null unit flagged", () -> x.exchange("a", 1, null));
        Thread t = start(() -> { try { x.exchange("t"); } catch (Exception e) { } });
        until(() -> t.getState() == Thread.State.WAITING);
        flag(); show("exchange flagged, partner waiting", () -> x.exchange("m"));
        show("partner still there", () -> x.exchange("m"));
        t.join();
    }

    static void barrierOrder() throws Exception {
        CyclicBarrier b = new CyclicBarrier(2);
        flag(); show("barrier zero flagged", () -> b.await(0, TimeUnit.SECONDS));
        show("broken by that", b::isBroken);
        flag(); show("broken and flagged", b::await);
        b.reset();
        flag(); show("barrier null unit flagged", () -> b.await(1, null));
        show("not broken by a null unit", b::isBroken);
        CyclicBarrier one = new CyclicBarrier(1);
        flag(); show("one party flagged", one::await);
        show("one party broken", one::isBroken);
        CyclicBarrier k = new CyclicBarrier(2);
        Thread keep = start(() -> { try { k.await(); } catch (Exception e) { } });
        until(() -> k.getNumberWaiting() == 1);
        flag(); show("last arrival flagged", k::await);
        show("last arrival broke it", k::isBroken);
        keep.join();
        CyclicBarrier er = new CyclicBarrier(1, () -> { throw new Error("boom"); });
        show("action Error", er::await);
        show("action Error broke it", er::isBroken);
    }

    static void barrierAction() throws Exception {
        CyclicBarrier[] h = new CyclicBarrier[1];
        String[] log = new String[2];
        h[0] = new CyclicBarrier(2, () -> {
            log[0] = "waiting " + h[0].getNumberWaiting() + " broken " + h[0].isBroken();
            h[0].reset();
            log[1] = "waiting " + h[0].getNumberWaiting() + " broken " + h[0].isBroken();
        });
        String[] other = new String[1];
        Thread o = blocked(other, h[0]::await);
        show("action resets: last arrival", h[0]::await);
        o.join();
        show("action resets: other", () -> other[0]);
        show("seen by action", () -> log[0]);
        show("seen by action after its reset", () -> log[1]);

        CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        CyclicBarrier b = new CyclicBarrier(1, () -> {
            entered.countDown();
            try { go.await(); } catch (InterruptedException e) { }
        });
        Thread arriver = start(() -> { try { b.await(); } catch (Exception e) { } });
        entered.await();
        show("parties during action", b::getParties);
        String[] r = new String[5];
        Thread waiting = blocked(r, b::getNumberWaiting);
        String[] r1 = new String[1];
        Thread broken = blocked(r1, b::isBroken);
        String[] r2 = new String[1];
        Thread reset = blocked(r2, () -> { b.reset(); return "done"; });
        String[] r3 = new String[1];
        Thread timed = blocked(r3, () -> b.await(1, TimeUnit.MILLISECONDS));
        String[] r4 = new String[1];
        Thread late = blocked(r4, b::await);
        late.interrupt();
        Thread.sleep(5);
        until(() -> late.getState() == Thread.State.WAITING);
        show("interrupted while the action runs: still", late::getState);
        go.countDown();
        for (Thread t : new Thread[] {arriver, waiting, broken, reset, timed, late}) t.join();
        show("getNumberWaiting waited", () -> r[0]);
        show("isBroken waited", () -> r1[0].startsWith("= "));
        show("reset waited", () -> r2[0]);
        show("late interrupted arrival", () -> r4[0].replaceAll("= \\d", "= index"));
    }

    static void semaphores() throws Exception {
        Semaphore s = new Semaphore(1);
        flag(); show("negative flagged", () -> { s.acquire(-1); return "no"; });
        flag(); show("timed null unit flagged", () -> s.tryAcquire(1, null));
        flag(); show("negative and null unit", () -> s.tryAcquire(-1, 1, null));
        flag(); show("zero permits flagged", () -> { s.acquire(0); return "ok"; });
        flag(); show("release flagged", () -> { s.release(0); return s.availablePermits(); });
        show("tryAcquire 0 on negative", () -> new Semaphore(-1).tryAcquire(0));
        Semaphore big = new Semaphore(Integer.MAX_VALUE);
        show("release 0 at the maximum", () -> { big.release(0); return big.availablePermits(); });
        Semaphore m = new Semaphore(Integer.MIN_VALUE);
        show("release up from the minimum", () -> { m.release(Integer.MAX_VALUE); return m.availablePermits(); });
    }

    /** One release of several permits reaches every waiter, though it wakes only the first. */
    static void permitChain() throws Exception {
        for (boolean fair : new boolean[] {false, true}) {
            Semaphore s = new Semaphore(0, fair);
            CountDownLatch got = new CountDownLatch(4);
            for (int i = 0; i < 4; i++) start(() -> { s.acquireUninterruptibly(); got.countDown(); });
            until(() -> s.getQueueLength() == 4);
            s.release(4);
            show("permits reach all four, fair=" + fair, () -> got.await(30, TimeUnit.SECONDS));
        }
    }

    /** A head that gives up passes the wake on: the one behind it can use the permit it could not. */
    static void headLeaves() throws Exception {
        for (boolean timedHead : new boolean[] {false, true}) {
            Semaphore s = new Semaphore(0, true);
            Thread big = start(() -> {
                try {
                    if (timedHead) s.tryAcquire(2, 100, TimeUnit.MILLISECONDS);
                    else s.acquire(2);
                } catch (InterruptedException e) { }
            });
            until(() -> s.getQueueLength() == 1);
            CountDownLatch got = new CountDownLatch(1);
            start(() -> { s.acquireUninterruptibly(); got.countDown(); });
            until(() -> s.getQueueLength() >= 2 || !big.isAlive());
            s.release();
            if (!timedHead) big.interrupt();
            show("next served after head left, timed=" + timedHead, () -> got.await(30, TimeUnit.SECONDS));
        }
    }

    /** A fair queue binds a newcomer from the first moment: no sleep between the steps. */
    static void fairAtOnce() throws Exception {
        for (int i = 0; i < 5; i++) {
            Semaphore s = new Semaphore(1, true);
            Thread big = start(() -> s.acquireUninterruptibly(2));
            while (s.getQueueLength() == 0) { }
            show("fair timed zero behind a new head", () -> s.tryAcquire(0, TimeUnit.SECONDS));
            s.release();
            big.join();
        }
    }

    static void random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        show("wide int range, both signs", () -> {
            boolean neg = false, pos = false;
            for (int i = 0; i < 2000; i++) { if (r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE) < 0) neg = true; else pos = true; }
            return neg && pos;
        });
        show("wide int range varies", () -> {
            int first = r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
            for (int i = 0; i < 100; i++) if (r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE) != first) return true;
            return false;
        });
        show("wide long range, both signs", () -> {
            boolean neg = false, pos = false;
            for (int i = 0; i < 2000; i++) { if (r.nextLong(Long.MIN_VALUE, Long.MAX_VALUE) < 0) neg = true; else pos = true; }
            return neg && pos;
        });
        show("int min to zero", () -> { for (int i = 0; i < 500; i++) { if (r.nextInt(Integer.MIN_VALUE, 0) >= 0) return false; } return true; });
        show("long min to zero", () -> { for (int i = 0; i < 500; i++) { if (r.nextLong(Long.MIN_VALUE, 0) >= 0) return false; } return true; });
        show("widest double range", () -> {
            for (int i = 0; i < 500; i++) {
                double v = r.nextDouble(-Double.MAX_VALUE, Double.MAX_VALUE);
                if (!(v >= -Double.MAX_VALUE && v < Double.MAX_VALUE)) return false;
            }
            return true;
        });
        show("double up to infinity", () -> r.nextDouble(0, Double.POSITIVE_INFINITY) < Double.POSITIVE_INFINITY);
        show("double from minus infinity", () -> r.nextDouble(Double.NEGATIVE_INFINITY, 0) > Double.NEGATIVE_INFINITY);
    }

    static void units() throws Exception {
        Object m = new Object();
        synchronized (m) { TimeUnit.MICROSECONDS.timedWait(m, 1500); }
        flag(); show("timedWait flagged", () -> { synchronized (m) { TimeUnit.SECONDS.timedWait(m, 1); } return "no"; });
        flag(); show("timedWait zero flagged", () -> { synchronized (m) { TimeUnit.SECONDS.timedWait(m, 0); } return "ok"; });
        Thread t = new Thread(() -> { });
        t.start();
        t.join();
        flag(); show("timedJoin dead thread flagged", () -> { TimeUnit.SECONDS.timedJoin(t, 1); return "ok"; });
        show("micros sleep", () -> { TimeUnit.MICROSECONDS.sleep(1500); return "ok"; });
    }

    public static void main(String[] args) throws Exception {
        latches();
        exchangers();
        barrierOrder();
        barrierAction();
        semaphores();
        permitChain();
        headLeaves();
        fairAtOnce();
        random();
        units();
    }
}
