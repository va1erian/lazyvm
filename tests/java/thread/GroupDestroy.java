package thread;

import java.util.concurrent.CountDownLatch;

/** ThreadGroup.destroy: who refuses it, what it leaves behind, what a destroyed group refuses. */
public class GroupDestroy {
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
        ThreadGroup p = g.getParent();
        return g.getName() + " destroyed=" + g.isDestroyed() + " parent=" + (p == null ? "null" : p.getName())
                + " active=" + g.activeCount() + " groups=" + g.activeGroupCount() + " maxpri=" + g.getMaxPriority();
    }

    /** A thread that waits to be let go, so that every state printed here is settled. */
    static class Held extends Thread {
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch up = new CountDownLatch(1);

        Held(ThreadGroup g, String n) {
            super(g, n);
        }

        @Override
        public void run() {
            up.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                System.out.println(e);
            }
        }

        Held begin() throws Exception {
            start();
            up.await();
            return this;
        }

        void finish() throws Exception {
            go.countDown();
            join();
        }
    }

    static void basics(ThreadGroup top) {
        ThreadGroup a = new ThreadGroup(top, "a");
        show("fresh", () -> st(a));
        show("destroy", () -> { a.destroy(); return st(a); });
        show("again", () -> { a.destroy(); return st(a); });
        show("top", () -> st(top));
        show("top's list", () -> top.enumerate(new ThreadGroup[4]));
        show("new Thread", () -> new Thread(a, "x"));
        show("new ThreadGroup", () -> new ThreadGroup(a, "x"));
        show("null name", () -> new Thread(a, (String) null));
        show("setMaxPriority", () -> { a.setMaxPriority(3); return st(a); });
        show("setDaemon", () -> { a.setDaemon(true); return a.isDaemon(); });
        show("enumerate", () -> a.enumerate(new Thread[2]));
        show("enumerate groups", () -> a.enumerate(new ThreadGroup[2]));
        show("parentOf", () -> a.parentOf(a) + " " + top.parentOf(a));
        show("toString", () -> a.toString());
        show("interrupt", () -> { a.interrupt(); return "ok"; });
    }

    static void busy(ThreadGroup top) throws Exception {
        ThreadGroup b = new ThreadGroup(top, "b");
        Held h = new Held(b, "h").begin();
        show("live thread", () -> { b.destroy(); return "ok"; });
        show("still", () -> st(b));
        h.finish();
        show("thread ended", () -> st(b));
        show("destroy now", () -> { b.destroy(); return st(b); });

        ThreadGroup f = new ThreadGroup(top, "f");
        ThreadGroup f1 = new ThreadGroup(f, "f1");
        Held hf = new Held(f, "hf").begin();
        show("busy parent", () -> { f.destroy(); return "ok"; });
        show("its subgroup untouched", () -> st(f1));
        hf.finish();

        ThreadGroup d = new ThreadGroup(top, "d");
        ThreadGroup d1 = new ThreadGroup(d, "d1");
        ThreadGroup d11 = new ThreadGroup(d1, "d11");
        show("deep", () -> { d.destroy(); return st(d) + " | " + st(d1) + " | " + st(d11); });

        ThreadGroup e = new ThreadGroup(top, "e");
        ThreadGroup e1 = new ThreadGroup(e, "e1");
        show("child first", () -> { e1.destroy(); return st(e) + " | " + st(e1); });
        show("then parent", () -> { e.destroy(); return st(e); });
    }

    /** A destroy that fails part way is not undone. */
    static void halfway(ThreadGroup top) throws Exception {
        ThreadGroup c = new ThreadGroup(top, "c");
        ThreadGroup c1 = new ThreadGroup(c, "c1");
        ThreadGroup c2 = new ThreadGroup(c, "c2");
        ThreadGroup c3 = new ThreadGroup(c, "c3");
        Held h = new Held(c2, "hc").begin();
        int before = top.activeGroupCount();
        show("busy middle", () -> { c.destroy(); return "ok"; });
        show("c", () -> st(c));
        show("c1", () -> st(c1));
        show("c2", () -> st(c2));
        show("c3", () -> st(c3));
        show("top's count change", () -> top.activeGroupCount() - before);
        show("top sees its thread", () -> top.enumerate(new Thread[4]));
        show("c's ceiling stops at c", () -> { c.setMaxPriority(2); return st(c) + " | " + st(c3); });
        show("top's ceiling reaches c", () -> { top.setMaxPriority(1); return st(c) + " | " + st(c3); });
        top.setMaxPriority(10);
        show("c interrupts nobody", () -> { c.interrupt(); return h.isInterrupted(); });
        h.finish();
        show("destroy again", () -> { c.destroy(); return "ok"; });
        show("c3 takes threads", () -> new Thread(c3, "o").getThreadGroup().getName());
        show("c2 goes", () -> { c2.destroy(); return st(c2) + " | " + st(c); });
        show("c3 goes", () -> { c3.destroy(); return st(c3) + " | " + st(c); });
        show("top still lists c", () -> top.activeGroupCount() - before);
    }

    static String names(Object[] list) {
        StringBuilder b = new StringBuilder();
        for (Object o : list) {
            b.append(o == null ? "-" : o instanceof Thread ? ((Thread) o).getName() : ((ThreadGroup) o).getName()).append(' ');
        }
        return b.toString();
    }

    /** Listing through a half-destroyed group restarts the count; stack traces still find everyone. */
    static void throughHalfway(ThreadGroup top) throws Exception {
        Held own = new Held(top, "own").begin();
        ThreadGroup c = new ThreadGroup(top, "c");
        Held cut = new Held(new ThreadGroup(c, "busy"), "cut").begin();
        show("destroy", () -> { c.destroy(); return "ok"; });
        ThreadGroup after = new ThreadGroup(top, "after");
        Held ha = new Held(after, "ha").begin();
        Thread[] threads = new Thread[4];
        show("threads", () -> top.enumerate(threads) + " " + names(threads));
        ThreadGroup[] groups = new ThreadGroup[4];
        show("groups", () -> top.enumerate(groups) + " " + names(groups));
        show("stack traces find them", () -> {
            java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            return all.containsKey(own) + " " + all.containsKey(cut) + " " + all.containsKey(ha);
        });
        cut.finish();
        own.finish();
        ha.finish();
    }

    /** interrupt and stack traces do not go through what a subclass overrides. */
    static void overridden() throws Exception {
        ThreadGroup g = new ThreadGroup("lying") {
            @Override
            public int activeCount() {
                return -5;
            }

            @Override
            public int enumerate(Thread[] list) {
                return 0;
            }

            @Override
            public int enumerate(Thread[] list, boolean recurse) {
                return 0;
            }
        };
        final boolean[] hit = new boolean[1];
        final CountDownLatch up = new CountDownLatch(1);
        Thread t = new Thread(g, () -> {
            up.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                hit[0] = true;
            }
        }, "lied about");
        t.start();
        up.await();
        show("stack traces", () -> Thread.getAllStackTraces().containsKey(t));
        show("interrupt", () -> { g.interrupt(); t.join(); return hit[0]; });
    }

    static void unstarted(ThreadGroup top) throws Exception {
        ThreadGroup u = new ThreadGroup(top, "u");
        Thread t = new Thread(u, "ut");
        show("not counted", () -> st(u));
        show("no hold on destroy", () -> { u.destroy(); return st(u); });
        show("its group", () -> t.getThreadGroup());
        show("its toString", () -> t.toString());
        show("setPriority", () -> { t.setPriority(7); return t.getPriority(); });
        show("start", () -> { t.start(); t.join(); return t.getState(); });
        show("after", () -> t.getState() + " alive=" + t.isAlive() + " " + t.getThreadGroup());
        show("start again", () -> { t.start(); return "ok"; });
    }

    /** The name comes first, so a refused thread uses one; its id comes after. */
    static void refusedCosts(ThreadGroup top) throws Exception {
        ThreadGroup gone = new ThreadGroup(top, "gone");
        gone.destroy();
        int name = number(new Thread());
        show("refused auto name", () -> new Thread(gone, (Runnable) null));
        System.out.println("names used " + (number(new Thread()) - name - 1));
        long id = new Thread().getId();
        show("refused", () -> new Thread(gone, "n"));
        System.out.println("ids used " + (new Thread().getId() - id - 1));
    }

    static int number(Thread t) {
        return Integer.parseInt(t.getName().substring("Thread-".length()));
    }

    static void fromInside(ThreadGroup top) throws Exception {
        ThreadGroup i = new ThreadGroup(top, "i");
        Thread t = new Thread(i, () -> show("own group", () -> { Thread.currentThread().getThreadGroup().destroy(); return "ok"; }), "it");
        t.start();
        t.join();
        show("after", () -> st(i));
        ThreadGroup main = Thread.currentThread().getThreadGroup();
        show("main group", () -> { main.destroy(); return "ok"; });
        show("system group", () -> { main.getParent().destroy(); return "ok"; });
        System.out.println("destroyed " + main.isDestroyed() + " " + main.getParent().isDestroyed());
    }

    public static void main(String[] args) throws Exception {
        ThreadGroup top = new ThreadGroup("top");
        basics(top);
        busy(top);
        halfway(new ThreadGroup("half"));
        throughHalfway(new ThreadGroup("through"));
        overridden();
        unstarted(top);
        refusedCosts(top);
        fromInside(top);
    }
}
