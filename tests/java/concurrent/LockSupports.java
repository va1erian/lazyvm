package concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** LockSupport: the single permit, blockers, interrupts, timed returns. */
public class LockSupports {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    interface Cond { boolean ok() throws Exception; }

    static void until(Cond c) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.ok()) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static boolean returned(Runnable r) throws Exception {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        t.join(50);
        return !t.isAlive();
    }

    static void permit() throws Exception {
        LockSupport.unpark(Thread.currentThread());
        LockSupport.park();
        show("permit consumed by park", () -> "returned");
        LockSupport.unpark(Thread.currentThread());
        LockSupport.unpark(Thread.currentThread());
        LockSupport.park();
        long t0 = System.nanoTime();
        LockSupport.parkNanos(20_000_000L);
        show("permits do not stack", () -> System.nanoTime() - t0 >= 10_000_000L);
        LockSupport.unpark(null);
        show("unpark null", () -> "ok");
        Thread unstarted = new Thread(() -> { });
        LockSupport.unpark(unstarted);
        show("unpark unstarted", unstarted::getState);
        unstarted.start();
        unstarted.join();
        LockSupport.unpark(unstarted);
        show("unpark dead", unstarted::getState);
    }

    static void timed() {
        long t0 = System.nanoTime();
        LockSupport.parkNanos(0);
        LockSupport.parkNanos(-1);
        LockSupport.parkUntil(0);
        LockSupport.parkUntil(System.currentTimeMillis() - 1000);
        show("non-positive return at once", () -> System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5));
        long t1 = System.nanoTime();
        LockSupport.parkUntil(System.currentTimeMillis() + 20);
        show("parkUntil waits", () -> System.nanoTime() - t1 >= 5_000_000L);
    }

    static void blockers() throws Exception {
        Object blocker = "the blocker";
        show("current no blocker", () -> LockSupport.getBlocker(Thread.currentThread()));
        show("getBlocker null", () -> LockSupport.getBlocker(null));
        Thread t = new Thread(() -> LockSupport.park(blocker));
        t.start();
        until(() -> t.getState() == Thread.State.WAITING);
        show("blocker while parked", () -> LockSupport.getBlocker(t));
        show("state parked", t::getState);
        LockSupport.unpark(t);
        t.join();
        show("blocker after", () -> LockSupport.getBlocker(t));
        Thread timed = new Thread(() -> LockSupport.parkNanos(blocker, TimeUnit.SECONDS.toNanos(60)));
        timed.start();
        until(() -> timed.getState() == Thread.State.TIMED_WAITING);
        show("blocker timed", () -> LockSupport.getBlocker(timed));
        show("state timed", timed::getState);
        LockSupport.unpark(timed);
        timed.join();
        Thread until = new Thread(() -> LockSupport.parkUntil(blocker, System.currentTimeMillis() + 60_000));
        until.start();
        until(() -> until.getState() == Thread.State.TIMED_WAITING);
        show("blocker until", () -> LockSupport.getBlocker(until));
        LockSupport.unpark(until);
        until.join();
        Thread none = new Thread(() -> LockSupport.park((Object) null));
        none.start();
        until(() -> none.getState() == Thread.State.WAITING);
        show("null blocker", () -> LockSupport.getBlocker(none));
        LockSupport.unpark(none);
        none.join();
    }

    static void interrupts() throws Exception {
        String[] seen = new String[1];
        Thread t = new Thread(() -> {
            LockSupport.park();
            seen[0] = "flag=" + Thread.currentThread().isInterrupted();
        });
        t.start();
        until(() -> t.getState() == Thread.State.WAITING);
        t.interrupt();
        t.join();
        show("interrupt wakes park, keeps flag", () -> seen[0]);
        Thread.currentThread().interrupt();
        LockSupport.park();
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(60));
        LockSupport.parkUntil(System.currentTimeMillis() + 60_000);
        show("flagged parks return", () -> Thread.interrupted());
        show("parks without unpark stay", () -> returned(LockSupport::park));
    }

    public static void main(String[] args) throws Exception {
        permit();
        timed();
        blockers();
        interrupts();
    }
}
