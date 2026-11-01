package thread;

/** When a daemon ThreadGroup destroys itself, and what holds it back. */
public class GroupDaemon {
    interface Act {
        Object run() throws Exception;
    }

    static void show(String what, Act a) {
        try {
            System.out.println(what + ": " + a.run());
        } catch (Throwable t) {
            System.out.println(what + ": threw " + t);
        }
    }

    static String st(ThreadGroup g) {
        return g.getName() + " destroyed=" + g.isDestroyed() + " daemon=" + g.isDaemon()
                + " active=" + g.activeCount() + " groups=" + g.activeGroupCount();
    }

    static ThreadGroup daemon(ThreadGroup parent, String name) {
        ThreadGroup g = new ThreadGroup(parent, name);
        g.setDaemon(true);
        return g;
    }

    static void runOne(ThreadGroup g) throws Exception {
        Thread t = new Thread(g, "one");
        t.start();
        t.join();
    }

    static void lastThread(ThreadGroup top) throws Exception {
        ThreadGroup g = daemon(top, "g");
        show("empty stays", () -> st(g));
        GroupDestroy.Held h1 = new GroupDestroy.Held(g, "h1").begin();
        GroupDestroy.Held h2 = new GroupDestroy.Held(g, "h2").begin();
        show("two running", () -> st(g));
        h1.finish();
        show("one ended", () -> st(g));
        h2.finish();
        show("both ended", () -> st(g));
        show("ended thread", () -> h2.getThreadGroup() + " " + h2.getState());
        show("gone from top", () -> top.activeGroupCount());
        show("new Thread", () -> new Thread(g, "x"));
        show("destroy", () -> { g.destroy(); return "ok"; });
    }

    static void unstartedHold(ThreadGroup top) throws Exception {
        ThreadGroup g = daemon(top, "gu");
        Thread a = new Thread(g, "a");
        runOne(g);
        show("unstarted holds it", () -> st(g));
        Thread b = new Thread(g, "b");
        a.start();
        a.join();
        show("one of two ran", () -> st(g));
        b.start();
        b.join();
        show("all ran", () -> st(g));

        ThreadGroup v = daemon(top, "gv");
        Thread vt = new Thread(v, "vv");
        show("destroy ignores unstarted", () -> { v.destroy(); return st(v); });
        show("its start", () -> { vt.start(); return "ok"; });

        ThreadGroup x = daemon(top, "x");
        ThreadGroup x1 = new ThreadGroup(x, "x1");
        Thread xt = new Thread(x, "xu");
        x1.destroy();
        show("unstarted holds it, subgroup gone", () -> st(x));
        xt.start();
        xt.join();
        show("then it ran", () -> st(x));
    }

    /** Only the flag at the moment the last thread leaves matters. */
    static void flagTiming(ThreadGroup top) throws Exception {
        ThreadGroup a = new ThreadGroup(top, "late");
        GroupDestroy.Held h = new GroupDestroy.Held(a, "h").begin();
        a.setDaemon(true);
        h.finish();
        show("set while running", () -> st(a));

        ThreadGroup b = daemon(top, "cleared");
        h = new GroupDestroy.Held(b, "h").begin();
        b.setDaemon(false);
        h.finish();
        show("cleared while running", () -> st(b));
        b.setDaemon(true);
        show("set when idle", () -> st(b));
    }

    static void subgroups(ThreadGroup top) throws Exception {
        ThreadGroup p = daemon(top, "p");
        ThreadGroup p1 = new ThreadGroup(p, "p1");
        show("inherits", () -> st(p1));
        runOne(p1);
        show("cascade", () -> st(p1) + " | " + st(p));

        ThreadGroup q = daemon(top, "q");
        ThreadGroup q1 = new ThreadGroup(q, "q1");
        show("destroy the last subgroup", () -> { q1.destroy(); return st(q1) + " | " + st(q); });

        ThreadGroup r = daemon(top, "r");
        ThreadGroup r1 = new ThreadGroup(r, "r1");
        ThreadGroup r2 = new ThreadGroup(r, "r2");
        r1.destroy();
        show("one of two", () -> st(r));
        r2.destroy();
        show("both", () -> st(r));

        ThreadGroup s = daemon(top, "s");
        ThreadGroup s1 = new ThreadGroup(s, "s1");
        s1.setDaemon(false);
        runOne(s);
        show("subgroup holds it", () -> st(s) + " | " + st(s1));
        s1.destroy();
        show("then it goes", () -> st(s) + " | " + st(s1));

        ThreadGroup w = new ThreadGroup(top, "w");
        ThreadGroup w1 = daemon(w, "w1");
        runOne(w1);
        show("parent not a daemon", () -> st(w) + " | " + st(w1));

        ThreadGroup y = daemon(top, "y");
        GroupDestroy.Held h = new GroupDestroy.Held(y, "yh").begin();
        new ThreadGroup(y, "y1").destroy();
        show("live thread holds it", () -> st(y));
        h.finish();
        show("then it ended", () -> st(y));
    }

    /** A half-destroyed group never leaves its parent, and so holds a daemon parent. */
    static void halfway(ThreadGroup top) throws Exception {
        ThreadGroup d = daemon(top, "dtop");
        ThreadGroup c = new ThreadGroup(d, "dc");
        ThreadGroup c1 = new ThreadGroup(c, "dc1");
        GroupDestroy.Held h = new GroupDestroy.Held(c1, "hd").begin();
        show("destroy", () -> { c.destroy(); return "ok"; });
        show("after", () -> st(d) + " | " + st(c) + " | " + st(c1));
        h.finish();
        show("thread ended", () -> st(d) + " | " + st(c) + " | " + st(c1));
        show("parent destroy", () -> { d.destroy(); return "ok"; });
        show("parent after", () -> st(d));
    }

    static class Thrower extends Thread {
        Thrower(Runnable r) {
            super(r, "thrower");
        }

        @Override
        public ClassLoader getContextClassLoader() {
            throw new IllegalStateException("no loader");
        }
    }

    /** A constructor that fails still leaves its thread counted as unstarted. */
    static void failedConstructors(ThreadGroup top) throws Exception {
        ThreadGroup f = daemon(top, "f");
        ThreadGroup gone = new ThreadGroup(top, "gone");
        gone.destroy();
        Thread th = new Thrower(() -> {
            show("loader throws", () -> new Thread(f, "x"));
            show("destroyed group first", () -> new Thread(gone, "x"));
        });
        th.start();
        th.join();
        runOne(f);
        show("held by that", () -> st(f));

        ThreadGroup f2 = daemon(top, "f2");
        InheritableThreadLocal<String> local = new InheritableThreadLocal<String>() {
            @Override
            protected String childValue(String p) {
                throw new IllegalStateException("no child");
            }
        };
        local.set("v");
        show("childValue throws", () -> new Thread(f2, "x"));
        local.remove();
        runOne(f2);
        show("held by that", () -> st(f2));
    }

    static void handler(ThreadGroup top) throws Exception {
        ThreadGroup g = new ThreadGroup(top, "ex") {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                System.out.println("handler: " + e + " group=" + t.getThreadGroup().getName() + " destroyed=" + isDestroyed());
            }
        };
        g.setDaemon(true);
        Thread t = new Thread(g, () -> {
            throw new RuntimeException("boom");
        }, "et");
        t.start();
        t.join();
        show("after", () -> st(g));
    }

    static void concurrent(ThreadGroup top) throws Exception {
        int before = top.activeGroupCount();
        for (int round = 0; round < 50; round++) {
            ThreadGroup m = daemon(top, "m");
            ThreadGroup n = new ThreadGroup(m, "n");
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(i % 2 == 0 ? m : n, "w" + i);
            }
            for (Thread t : ts) {
                t.start();
            }
            for (Thread t : ts) {
                t.join();
            }
            if (!m.isDestroyed() || !n.isDestroyed()) {
                System.out.println("not destroyed " + round);
            }
        }
        System.out.println("left behind " + (top.activeGroupCount() - before));
    }

    public static void main(String[] args) throws Exception {
        ThreadGroup top = new ThreadGroup("top");
        lastThread(new ThreadGroup("one"));
        unstartedHold(top);
        flagTiming(top);
        subgroups(top);
        halfway(top);
        failedConstructors(top);
        handler(top);
        concurrent(top);
    }
}
