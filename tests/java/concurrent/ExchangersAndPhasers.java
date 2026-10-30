package concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/** Exchanger and Phaser: pairing, timeouts, phase numbers, registration errors, tiers, termination. */
public class ExchangersAndPhasers {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) {
        return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H");
    }

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

    static void exchanger() throws Exception {
        Exchanger<String> x = new Exchanger<>();
        String[] res = new String[1];
        Thread t = new Thread(() -> {
            try { res[0] = x.exchange("from t"); } catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        t.start();
        show("main receives", () -> x.exchange("from main"));
        t.join();
        show("t receives", () -> res[0]);
        Thread nul = new Thread(() -> {
            try { res[0] = "got " + x.exchange(null); } catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        nul.start();
        show("null both ways", () -> x.exchange(null));
        nul.join();
        show("t got null", () -> res[0]);
        Thread timed = new Thread(() -> {
            try { res[0] = x.exchange("timed", 30, TimeUnit.SECONDS); } catch (Exception e) { res[0] = n(e); }
        });
        timed.start();
        show("timed pairs", () -> x.exchange("m", 30, TimeUnit.SECONDS));
        timed.join();
        show("timed got", () -> res[0]);
        show("timeout", () -> x.exchange("lonely", 2, TimeUnit.MILLISECONDS));
        show("zero timeout", () -> x.exchange("lonely", 0, TimeUnit.SECONDS));
        show("null unit", () -> x.exchange("lonely", 1, null));
        Thread.currentThread().interrupt();
        show("flagged", () -> x.exchange("a"));
        Thread.currentThread().interrupt();
        show("timed flagged", () -> x.exchange("a", 1, TimeUnit.SECONDS));
        show("cleared", () -> Thread.currentThread().isInterrupted());
        Thread i = new Thread(() -> {
            try { res[0] = x.exchange("i"); }
            catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        i.start();
        until(() -> i.getState() == Thread.State.WAITING || i.getState() == Thread.State.TIMED_WAITING);
        i.interrupt();
        i.join();
        show("interrupted", () -> res[0]);
        Thread after = new Thread(() -> {
            try { res[0] = x.exchange("after"); } catch (InterruptedException e) { res[0] = "interrupted"; }
        });
        after.start();
        show("usable after interrupt", () -> x.exchange("main"));
        after.join();
    }

    static void phaseBasics() throws Exception {
        show("negative parties", () -> new Phaser(-1));
        show("too many parties", () -> new Phaser(65536));
        show("max parties", () -> new Phaser(65535));
        Phaser p = new Phaser();
        show("empty", () -> p);
        show("phase", p::getPhase);
        show("parties", p::getRegisteredParties);
        show("arrived", p::getArrivedParties);
        show("unarrived", p::getUnarrivedParties);
        show("terminated", p::isTerminated);
        show("parent", p::getParent);
        show("root is self", () -> p.getRoot() == p);
        show("arrive unregistered", p::arrive);
        show("deregister unregistered", p::arriveAndDeregister);
        show("await-arrive unregistered", p::arriveAndAwaitAdvance);
        show("register", p::register);
        show("register 2", p::register);
        show("two", () -> p);
        show("bulk negative", () -> p.bulkRegister(-1));
        show("bulk zero", () -> p.bulkRegister(0));
        show("bulk", () -> p.bulkRegister(2));
        show("four", () -> p);
        show("bulk too many", () -> p.bulkRegister(65535));
        show("register at limit", () -> {
            Phaser full = new Phaser(65535);
            return full.register();
        });
        show("arrive", p::arrive);
        show("one arrived", () -> p);
        show("arrive 2", p::arrive);
        show("arrive 3", p::arrive);
        show("before last", () -> p.getArrivedParties() + " " + p.getUnarrivedParties() + " " + p.getPhase());
        show("arrive advances", p::arrive);
        show("advanced", () -> p);
        show("await past phase", () -> p.awaitAdvance(0));
        show("await negative", () -> p.awaitAdvance(-1));
        show("await far phase", () -> p.awaitAdvance(99));
        show("interruptibly past", () -> p.awaitAdvanceInterruptibly(0));
        show("timed past", () -> p.awaitAdvanceInterruptibly(0, 1, TimeUnit.SECONDS));
        show("timeout", () -> p.awaitAdvanceInterruptibly(1, 1, TimeUnit.MILLISECONDS));
        show("timed null unit current", () -> p.awaitAdvanceInterruptibly(1, 1, null));
        Thread.currentThread().interrupt();
        show("interruptibly flagged", () -> p.awaitAdvanceInterruptibly(1));
        Thread.currentThread().interrupt();
        show("interruptibly flagged past", () -> p.awaitAdvanceInterruptibly(0));
        show("flag kept", Thread::interrupted);
        Thread.currentThread().interrupt();
        show("timed flagged", () -> p.awaitAdvanceInterruptibly(1, 1, TimeUnit.SECONDS));
        show("deregister", p::arriveAndDeregister);
        show("three", () -> p);
        p.arriveAndDeregister();
        p.arriveAndDeregister();
        show("one left", () -> p);
        show("last deregister", p::arriveAndDeregister);
        show("zero parties terminates", () -> p);
        show("is terminated", p::isTerminated);
        show("phase negative", () -> p.getPhase() < 0);
        show("register terminated", () -> p.register() < 0);
        show("arrive terminated", () -> p.arrive() < 0);
        show("await terminated", () -> p.awaitAdvance(0) < 0);
        show("arriveAndAwait terminated", () -> p.arriveAndAwaitAdvance() < 0);
        show("parties after", p::getRegisteredParties);
    }

    static void flow() throws Exception {
        Phaser p = new Phaser(2);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        Thread t = new Thread(() -> {
            int ph = p.arriveAndAwaitAdvance();
            log.add("t phase " + ph);
        });
        t.start();
        until(() -> p.getArrivedParties() == 1);
        show("main arrives last", p::arriveAndAwaitAdvance);
        t.join();
        show("log", () -> log);
        show("phase", p::getPhase);
        String[] res = new String[1];
        Thread w = new Thread(() -> res[0] = "advanced to " + p.awaitAdvance(1));
        w.start();
        until(() -> w.getState() == Thread.State.WAITING);
        p.arrive();
        p.arrive();
        w.join();
        show("observer", () -> res[0]);
        Thread i = new Thread(() -> {
            try { res[0] = "phase " + p.awaitAdvanceInterruptibly(2); }
            catch (InterruptedException e) { res[0] = n(e) + " flag=" + Thread.currentThread().isInterrupted(); }
        });
        i.start();
        until(() -> i.getState() == Thread.State.WAITING);
        i.interrupt();
        i.join();
        show("interrupted", () -> res[0]);
        Thread keep = new Thread(() -> {
            res[0] = "phase " + p.awaitAdvance(2) + " flag=" + Thread.currentThread().isInterrupted();
        });
        keep.start();
        until(() -> keep.getState() == Thread.State.WAITING);
        keep.interrupt();
        Thread.sleep(5);
        until(() -> keep.getState() == Thread.State.WAITING);
        show("awaitAdvance ignores interrupt", keep::getState);
        p.arrive();
        p.arrive();
        keep.join();
        show("kept flag", () -> res[0]);
    }

    static void hooks() throws Exception {
        List<String> log = new ArrayList<>();
        Phaser limited = new Phaser(1) {
            @Override protected boolean onAdvance(int phase, int parties) {
                log.add("onAdvance " + phase + " " + parties);
                return phase >= 2;
            }
        };
        show("arrive 0", limited::arrive);
        show("arrive 1", limited::arrive);
        show("arrive 2", limited::arrive);
        show("hook log", () -> log);
        show("terminated by hook", limited::isTerminated);
        show("parties kept", limited::getRegisteredParties);
        Phaser forever = new Phaser() {
            @Override protected boolean onAdvance(int phase, int parties) { return false; }
        };
        forever.register();
        show("hook keeps alive", () -> { forever.arriveAndDeregister(); return forever.isTerminated() + " " + forever.getPhase(); });
        Phaser thrower = new Phaser(1) {
            @Override protected boolean onAdvance(int phase, int parties) { throw new IllegalStateException("hook"); }
        };
        show("hook throws", thrower::arrive);
        Phaser f = new Phaser(2);
        String[] res = new String[1];
        Thread w = new Thread(() -> res[0] = "negative " + (f.arriveAndAwaitAdvance() < 0));
        w.start();
        until(() -> f.getArrivedParties() == 1);
        f.forceTermination();
        w.join();
        show("force releases", () -> res[0]);
        show("force terminated", f::isTerminated);
        show("force keeps parties", f::getRegisteredParties);
        f.forceTermination();
        show("force twice", f::isTerminated);
    }

    static void tiers() {
        Phaser root = new Phaser();
        show("register under terminated parent", () -> {
            Phaser dead = new Phaser();
            dead.forceTermination();
            return new Phaser(dead, 1).isTerminated();
        });
        Phaser a = new Phaser(root, 1);
        Phaser b = new Phaser(root, 2);
        Phaser leaf = new Phaser(a, 0);
        show("root parties", root::getRegisteredParties);
        show("a parent", () -> a.getParent() == root);
        show("leaf root", () -> leaf.getRoot() == root);
        show("leaf no party yet", () -> a.getRegisteredParties());
        leaf.register();
        show("leaf registers up", () -> a.getRegisteredParties() + " " + root.getRegisteredParties());
        show("null parent", () -> new Phaser(null, 1).getParent());
        show("child arrive", a::arrive);
        leaf.arrive();
        show("a done, root waits", () -> a.getPhase() + " " + root.getPhase() + " " + root.getUnarrivedParties() + " " + root.getArrivedParties());
        show("a toString", () -> a);
        b.arrive();
        b.arrive();
        show("all advanced", () -> root.getPhase() + " " + a.getPhase() + " " + b.getPhase() + " " + leaf.getPhase());
        b.arriveAndDeregister();
        b.arriveAndDeregister();
        show("emptied child", () -> b.getRegisteredParties() + " " + root.getRegisteredParties() + " " + b.isTerminated());
        root.forceTermination();
        show("terminate from root", () -> a.isTerminated() + " " + b.isTerminated() + " " + leaf.isTerminated());
    }

    public static void main(String[] args) throws Exception {
        exchanger();
        phaseBasics();
        flow();
        hooks();
        tiers();
    }
}
