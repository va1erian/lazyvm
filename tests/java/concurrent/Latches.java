package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** CountDownLatch and CyclicBarrier: counts, toString, breakage, arrival indexes, interrupts. */
public class Latches {
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

    static void latch() throws Exception {
        show("negative", () -> new CountDownLatch(-1));
        CountDownLatch z = new CountDownLatch(0);
        show("zero", () -> z);
        z.await();
        show("zero timed await", () -> z.await(0, TimeUnit.SECONDS));
        CountDownLatch c = new CountDownLatch(2);
        show("toString", () -> c);
        show("count", c::getCount);
        show("timed await not reached", () -> c.await(1, TimeUnit.MILLISECONDS));
        show("zero wait", () -> c.await(0, TimeUnit.SECONDS));
        show("await null unit", () -> c.await(1, null));
        c.countDown();
        show("count 1", c::getCount);
        String[] res = new String[1];
        Thread w = new Thread(() -> {
            try { c.await(); res[0] = "released"; } catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        w.start();
        until(() -> w.getState() == Thread.State.WAITING);
        c.countDown();
        w.join();
        show("waiter", () -> res[0]);
        show("count 0", c::getCount);
        c.countDown();
        show("floors at zero", c::getCount);
        show("toString done", () -> c);
        show("await after", () -> c.await(0, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        show("await flagged, open", () -> { c.await(); return "returned"; });
        CountDownLatch closed = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        show("await flagged, closed", () -> { closed.await(); return "returned"; });
        show("cleared", () -> Thread.currentThread().isInterrupted());
        Thread.currentThread().interrupt();
        show("timed flagged", () -> closed.await(1, TimeUnit.SECONDS));
        Thread i = new Thread(() -> {
            try { closed.await(); res[0] = "released"; }
            catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        i.start();
        until(() -> i.getState() == Thread.State.WAITING);
        i.interrupt();
        i.join();
        show("interrupted waiter", () -> res[0]);
        show("count unchanged", closed::getCount);
    }

    static void barrierBasics() throws Exception {
        show("parties zero", () -> new CyclicBarrier(0));
        show("parties zero action", () -> new CyclicBarrier(-1, () -> { }));
        show("toString not overridden", () -> new CyclicBarrier(1).toString().startsWith("java.util.concurrent.CyclicBarrier@"));
        CyclicBarrier one = new CyclicBarrier(1);
        show("one party index", one::await);
        show("one party again", () -> one.await(1, TimeUnit.SECONDS));
        show("null unit", () -> one.await(1, null));
        CyclicBarrier nul = new CyclicBarrier(1, null);
        show("null action", nul::await);

        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CyclicBarrier b = new CyclicBarrier(3, () -> log.add("action on " + Thread.currentThread().getName()));
        show("parties", b::getParties);
        show("waiting", b::getNumberWaiting);
        show("broken", b::isBroken);
        Thread[] ts = new Thread[2];
        for (int i = 0; i < 2; i++) {
            String name = "t" + i;
            ts[i] = new Thread(() -> {
                try { log.add(name + " index " + b.await()); } catch (Exception e) { log.add(name + " " + e); }
            }, name);
            ts[i].start();
            int want = i + 1;
            until(() -> b.getNumberWaiting() == want);
        }
        show("waiting 2", b::getNumberWaiting);
        show("last index", b::await);
        for (Thread t : ts) t.join();
        Collections.sort(log);
        show("log", () -> log);
        show("reusable waiting", b::getNumberWaiting);
        show("reusable broken", b::isBroken);
    }

    static void breakage() throws Exception {
        CyclicBarrier b = new CyclicBarrier(2);
        show("timeout", () -> b.await(5, TimeUnit.MILLISECONDS));
        show("broken after timeout", b::isBroken);
        show("waiting after timeout", b::getNumberWaiting);
        show("await broken", b::await);
        show("timed await broken", () -> b.await(1, TimeUnit.SECONDS));
        b.reset();
        show("reset repairs", b::isBroken);
        String[] res = new String[2];
        Thread w = new Thread(() -> {
            try { res[0] = "index " + b.await(); } catch (Exception e) { res[0] = n(e); }
        });
        w.start();
        until(() -> b.getNumberWaiting() == 1);
        b.reset();
        w.join();
        show("reset breaks waiter", () -> res[0]);
        show("usable after reset", b::isBroken);
        show("zero timeout", () -> b.await(0, TimeUnit.SECONDS));
        b.reset();
        Thread.currentThread().interrupt();
        show("await flagged", b::await);
        show("broken after flagged", b::isBroken);
        show("cleared", () -> Thread.currentThread().isInterrupted());
        b.reset();
        CyclicBarrier b3 = new CyclicBarrier(3);
        Thread a1 = new Thread(() -> {
            try { res[0] = "index " + b3.await(); }
            catch (Exception e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        Thread a2 = new Thread(() -> {
            try { res[1] = "index " + b3.await(); }
            catch (Exception e) { res[1] = n(e); }
        });
        a1.start();
        until(() -> b3.getNumberWaiting() == 1);
        a2.start();
        until(() -> b3.getNumberWaiting() == 2);
        a1.interrupt();
        a1.join();
        a2.join();
        show("interrupted waiter", () -> res[0]);
        show("other waiter", () -> res[1]);
        show("broken", b3::isBroken);
        CyclicBarrier bad = new CyclicBarrier(1, () -> { throw new IllegalStateException("action"); });
        show("action throws", bad::await);
        show("action broke it", bad::isBroken);
        CyclicBarrier bad2 = new CyclicBarrier(2, () -> { throw new IllegalStateException("action"); });
        Thread o = new Thread(() -> {
            try { res[0] = "index " + bad2.await(); } catch (Exception e) { res[0] = n(e); }
        });
        o.start();
        until(() -> bad2.getNumberWaiting() == 1);
        show("last runs action", bad2::await);
        o.join();
        show("first sees broken", () -> res[0]);
    }

    public static void main(String[] args) throws Exception {
        latch();
        barrierBasics();
        breakage();
    }
}
