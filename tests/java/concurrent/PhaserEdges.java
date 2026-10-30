package concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

/** Corners of Phaser: forcing, late registration, argument order, and what happens around a blocked hook. */
public class PhaserEdges {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H").replaceAll("PhaserEdges\\$\\d+", "Hook"); }

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

    /** A call that must block: returns once its thread is parked; the array gets the result later. */
    static String[] parked(List<Thread> ts, Body body) throws Exception {
        String[] result = new String[1];
        Thread t = start(() -> {
            try { result[0] = "= " + n(body.run()); }
            catch (Throwable e) { result[0] = "! " + n(e); }
        });
        until(() -> t.getState() == Thread.State.WAITING);
        ts.add(t);
        return result;
    }

    /** A phaser whose hook waits for `go` once it has said so through `entered`. */
    static class Blocking extends Phaser {
        final CountDownLatch entered = new CountDownLatch(1), go = new CountDownLatch(1);
        Blocking(int parties) { super(parties); }
        @Override protected boolean onAdvance(int phase, int parties) {
            entered.countDown();
            try { go.await(); } catch (InterruptedException e) { }
            return super.onAdvance(phase, parties);
        }
    }

    static class Logging extends Phaser {
        final List<String> log = new ArrayList<>();
        boolean thrower;
        Logging(int n) { super(n); }
        Logging(Phaser parent, int n) { super(parent, n); }
        @Override protected boolean onAdvance(int ph, int parties) {
            log.add(ph + "," + parties);
            if (thrower) throw new IllegalStateException("hook");
            return super.onAdvance(ph, parties);
        }
    }

    static void forcing() {
        Phaser f = new Phaser(3);
        f.arrive();
        f.forceTermination();
        show("forced with an arrival", () -> f + " " + f.getArrivedParties() + " " + f.getUnarrivedParties());
        Phaser g = new Phaser(1);
        g.arrive();
        g.arrive();
        g.forceTermination();
        show("forced at phase 2", g::getPhase);
        show("register forced", g::register);
        show("arrive forced", g::arrive);
        show("deregister forced", g::arriveAndDeregister);
        show("bulk zero forced", () -> g.bulkRegister(0));
        show("bulk negative forced", () -> g.bulkRegister(-1));
        show("parties forced", g::getRegisteredParties);
    }

    static void argumentOrder() {
        Phaser g = new Phaser(1);
        show("interruptibly negative", () -> g.awaitAdvanceInterruptibly(-5));
        show("timed negative", () -> g.awaitAdvanceInterruptibly(-5, 0, TimeUnit.SECONDS));
        show("timed negative null unit", () -> g.awaitAdvanceInterruptibly(-5, 1, null));
        show("timed past null unit", () -> g.awaitAdvanceInterruptibly(7, 1, null));
        flag(); show("timed zero flagged", () -> g.awaitAdvanceInterruptibly(0, 0, TimeUnit.SECONDS));
        flag(); show("timed past flagged", () -> g.awaitAdvanceInterruptibly(7, 1, TimeUnit.SECONDS));
        show("timed zero", () -> g.awaitAdvanceInterruptibly(0, 0, TimeUnit.SECONDS));
        flag(); show("awaitAdvance past flagged", () -> g.awaitAdvance(5));
        flag(); show("arrive flagged", g::arrive);
    }

    static void hookThrows() throws Exception {
        Logging h = new Logging(1);
        h.thrower = true;
        show("hook throws", h::arrive);
        show("after the throw", () -> h + " " + h.getUnarrivedParties() + " " + h.isTerminated());
        h.thrower = false;
        show("arrive after the throw", h::arrive);
        show("hook not called again", () -> h.log);
        List<Thread> ts = new ArrayList<>();
        show("bulk zero after the throw", () -> h.bulkRegister(0));
        String[] reg = parked(ts, h::register);
        String[] adv = parked(ts, () -> h.awaitAdvance(0));
        h.forceTermination();
        for (Thread t : ts) t.join();
        show("register released by force", () -> reg[0]);
        show("awaitAdvance released by force", () -> adv[0]);
        show("end of that", () -> h);
    }

    static void tiers() throws Exception {
        Logging d = new Logging(2);
        show("deregister then arrive", () -> { d.arriveAndDeregister(); d.arrive(); return d + " " + d.log; });
        show("last deregister calls hook", () -> { d.arriveAndDeregister(); return d + " " + d.log; });
        Logging root = new Logging(0);
        Logging c1 = new Logging(root, 1);
        Logging c2 = new Logging(root, 1);
        c1.arrive();
        c2.arrive();
        show("hook only on the root", () -> root.log + " " + c1.log + " " + c2.log);
        c1.arrive();
        show("child arrived", () -> c1 + " " + root);
        List<Thread> ts = new ArrayList<>();
        String[] late = parked(ts, c1::register);
        c2.arrive();
        ts.get(0).join();
        show("register on an arrived child waited", () -> late[0] + " " + c1 + " " + root);
        Phaser top = new Phaser();
        Phaser kid = new Phaser(top);
        show("bulk on a child", () -> kid.bulkRegister(3) + " " + top + " " + kid);
        show("child too many", () -> kid.bulkRegister(65533));
        show("child after that", () -> top + " " + kid);
        Phaser full = new Phaser(65535);
        show("new child under a full parent", () -> new Phaser(full, 1));
        show("full parent after", () -> full);
        Phaser empty = new Phaser(full);
        show("register under a full parent", empty::register);
        show("child left empty", () -> empty + " " + full);
        Phaser solo = new Phaser(top, 1);
        show("child leaves", () -> solo.arriveAndDeregister() + " " + solo + " " + top);
        show("child rejoins", () -> solo.register() + " " + solo + " " + top);
        Phaser last = new Phaser();
        Phaser only = new Phaser(last, 1);
        show("only child leaves, root ends", () -> only.arriveAndDeregister() + " " + only + " " + last + " " + last.isTerminated());
        Phaser fullyArrived = new Phaser();
        Phaser a = new Phaser(fullyArrived, 1);
        new Phaser(fullyArrived, 1);
        a.arrive();
        show("arrived child counts", () -> a.getUnarrivedParties() + " " + a.getArrivedParties());
        show("arrived child arrives again", a::arrive);
    }

    static void blockedHook() throws Exception {
        Blocking p = new Blocking(1);
        Thread arriver = start(p::arrive);
        p.entered.await();
        show("during hook: state", () -> p + " " + p.getPhase() + " " + p.getRegisteredParties() + " " + p.getArrivedParties() + " " + p.getUnarrivedParties() + " " + p.isTerminated());
        show("during hook: arrive", p::arrive);
        show("during hook: deregister", p::arriveAndDeregister);
        show("during hook: arriveAndAwait", p::arriveAndAwaitAdvance);
        show("during hook: bulk zero", () -> p.bulkRegister(0));
        show("during hook: awaitAdvance of another phase", () -> p.awaitAdvance(1));
        show("during hook: timed wait", () -> p.awaitAdvanceInterruptibly(0, 1, TimeUnit.MILLISECONDS));
        List<Thread> ts = new ArrayList<>();
        String[] reg = parked(ts, p::register);
        String[] adv = parked(ts, () -> p.awaitAdvance(0));
        String[] intr = parked(ts, () -> p.awaitAdvanceInterruptibly(0));
        p.go.countDown();
        arriver.join();
        for (Thread t : ts) t.join();
        show("after hook: register", () -> reg[0]);
        show("after hook: awaitAdvance", () -> adv[0]);
        show("after hook: interruptibly", () -> intr[0]);
        show("after hook: state", () -> p);
    }

    static void blockedHookForced() throws Exception {
        Blocking p = new Blocking(1);
        Thread arriver = start(p::arrive);
        p.entered.await();
        show("force during hook", () -> { p.forceTermination(); return p + " " + p.isTerminated(); });
        p.go.countDown();
        arriver.join();
        show("hook ends after force", () -> p);
        Phaser self = new Phaser(1) {
            @Override protected boolean onAdvance(int ph, int parties) { forceTermination(); return false; }
        };
        show("hook forces", () -> self.arrive() + " " + self);
        Phaser both = new Phaser(2) {
            @Override protected boolean onAdvance(int ph, int parties) { forceTermination(); return true; }
        };
        both.arrive();
        show("hook forces and ends", () -> both.arrive() + " " + both);
    }

    static void blockedHookNoParties() throws Exception {
        Blocking p = new Blocking(1);
        Thread arriver = start(p::arriveAndDeregister);
        p.entered.await();
        show("empty during hook", () -> p);
        List<Thread> ts = new ArrayList<>();
        String[] reg = parked(ts, p::register);
        p.go.countDown();
        arriver.join();
        ts.get(0).join();
        show("register during empty hook", () -> reg[0]);
        show("empty after hook", () -> p);
    }

    static void blockedHookChildren() throws Exception {
        Blocking top = new Blocking(0);
        Phaser kid = new Phaser(top, 1);
        Phaser empty = new Phaser(top);
        Thread arriver = start(kid::arrive);
        top.entered.await();
        show("child during hook", () -> kid);
        show("child arrive during hook", kid::arrive);
        List<Thread> ts = new ArrayList<>();
        String[] k = parked(ts, kid::register);
        String[] e = parked(ts, empty::register);
        top.go.countDown();
        arriver.join();
        for (Thread t : ts) t.join();
        show("child register after hook", () -> k[0]);
        show("empty child register after hook", () -> e[0]);
        show("tree after hook", () -> top + " " + kid + " " + empty);
    }

    static void hookOnItself() {
        List<String> log = new ArrayList<>();
        Phaser p = new Phaser(1) {
            boolean once;
            @Override protected boolean onAdvance(int ph, int parties) {
                if (once) return false;
                once = true;
                log.add("seen " + this);
                try { arrive(); log.add("arrived"); } catch (IllegalStateException e) { log.add(n(e)); }
                try { log.add("timed " + awaitAdvanceInterruptibly(99, 1, TimeUnit.MILLISECONDS)); } catch (Exception e) { log.add(n(e)); }
                return false;
            }
        };
        p.arrive();
        show("hook's own view", () -> log);
        show("after own view", () -> p);
    }

    public static void main(String[] args) throws Exception {
        forcing();
        argumentOrder();
        hookThrows();
        tiers();
        blockedHook();
        blockedHookForced();
        blockedHookNoParties();
        blockedHookChildren();
        hookOnItself();
    }
}
