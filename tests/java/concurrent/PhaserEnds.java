package concurrent;

import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

/** Registering through tiers that are waiting, and how a hook's ending reads. */
public class PhaserEnds {
    static String st(Phaser f) {
        return f.getPhase() + " reg " + f.getRegisteredParties() + " arr " + f.getArrivedParties()
            + " un " + f.getUnarrivedParties() + " " + f.toString().replaceAll(".*@[0-9a-f]+", "");
    }

    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Waits until it blocks, in whichever way: on a monitor or waiting. */
    static void blocked(Thread t) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        for (;;) {
            Thread.State s = t.getState();
            if (s == Thread.State.WAITING || s == Thread.State.BLOCKED) return;
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    static Phaser ender(int parties) {
        return new Phaser(parties) {
            @Override protected boolean onAdvance(int phase, int registered) { return true; }
        };
    }

    public static void main(String[] args) throws Exception {
        tiers();
        hookEndings();
        lastArriver();
        System.exit(0);
    }

    static void tiers() throws Exception {
        Phaser r = new Phaser(1);
        Phaser m = new Phaser(r, 1);
        Phaser c = new Phaser(m);
        m.arrive();
        Thread a = start(c::register);
        Thread b = start(c::register);
        blocked(a);
        blocked(b);
        r.arrive();
        a.join(20_000);
        b.join(20_000);
        System.out.println("registered r " + st(r));
        System.out.println("registered m " + st(m));
        System.out.println("registered c " + st(c));
        c.arrive();
        c.arrive();
        m.arrive();
        r.arrive();
        System.out.println("advanced r " + st(r));
        System.out.println("advanced m " + st(m));
        System.out.println("advanced c " + st(c));

        Phaser held = new Phaser(1);
        Phaser mid = new Phaser(held, 1);
        Phaser leaf = new Phaser(mid);
        mid.arrive();
        int[] got = new int[1];
        Thread w = start(() -> got[0] = leaf.register());
        blocked(w);
        held.forceTermination();
        w.join(20_000);
        System.out.println("ended while waiting to register: " + got[0]);
        System.out.println("  leaf " + st(leaf));
        System.out.println("  mid " + st(mid));
    }

    static void hookEndings() {
        Phaser f = ender(3);
        f.arrive();
        f.arrive();
        f.arrive();
        System.out.println("hook ended " + st(f));
        Phaser left = ender(3);
        left.arrive();
        left.arrive();
        left.arriveAndDeregister();
        System.out.println("hook ended, one left " + st(left));
        Phaser last = ender(1);
        last.arriveAndDeregister();
        System.out.println("hook ended, none left " + st(last));
        Phaser ended = ender(2);
        ended.arrive();
        ended.arrive();
        System.out.println("new child after it " + st(new Phaser(ended, 2)));
        Phaser parent = ender(2);
        Phaser empty = new Phaser(parent);
        parent.arrive();
        parent.arrive();
        System.out.println("empty child " + st(empty));
        Phaser r = ender(0);
        Phaser c1 = new Phaser(r, 2);
        Phaser c2 = new Phaser(r, 1);
        c1.arrive();
        c1.arrive();
        c2.arrive();
        System.out.println("root of two " + st(r));
        System.out.println("child of two " + st(c1));
        System.out.println("other child " + st(c2));
        Phaser forced = ender(2);
        forced.arrive();
        forced.arrive();
        forced.forceTermination();
        System.out.println("forced after " + st(forced));
        Phaser later = new Phaser(2) {
            @Override protected boolean onAdvance(int phase, int registered) { return phase == 1; }
        };
        later.arrive();
        later.arrive();
        System.out.println("went on " + st(later));
        later.arrive();
        System.out.println("one in " + st(later));
        later.arrive();
        System.out.println("ended at 1 " + st(later));
    }

    static void lastArriver() throws Exception {
        Phaser f = ender(2);
        int[] other = new int[1];
        Thread w = start(() -> other[0] = f.arriveAndAwaitAdvance());
        blocked(w);
        System.out.println("last arriver gets " + f.arriveAndAwaitAdvance());
        w.join(20_000);
        System.out.println("the one before gets " + other[0]);
        System.out.println("afterwards " + f.arriveAndAwaitAdvance());
        Phaser r = ender(0);
        Phaser c = new Phaser(r, 1);
        System.out.println("on a child " + c.arriveAndAwaitAdvance() + " | " + st(c) + " | " + st(r));
        Phaser forces = new Phaser(1) {
            @Override protected boolean onAdvance(int phase, int registered) {
                forceTermination();
                return false;
            }
        };
        System.out.println("hook forces " + forces.arriveAndAwaitAdvance() + " " + st(forces));
        Phaser both = new Phaser(1) {
            @Override protected boolean onAdvance(int phase, int registered) {
                forceTermination();
                return true;
            }
        };
        System.out.println("hook forces and ends " + both.arriveAndAwaitAdvance() + " " + st(both));
        Phaser go = new Phaser(1) {
            @Override protected boolean onAdvance(int phase, int registered) { return false; }
        };
        System.out.println("hook goes on " + go.arriveAndAwaitAdvance() + " " + st(go));
    }
}
