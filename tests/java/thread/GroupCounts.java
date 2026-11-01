package thread;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/** Who a ThreadGroup counts and lists, and in what order. */
public class GroupCounts {
    /** No thread is one, so listing into an array of them fails. */
    static class Narrow extends Thread {
    }

    static final CountDownLatch END = new CountDownLatch(1);
    static final Runnable WAIT = new Runnable() {
        @Override
        public void run() {
            try {
                END.await();
            } catch (InterruptedException e) {
                System.out.println(e);
            }
        }
    };

    static void attempt(String what, Runnable r) {
        try {
            r.run();
            System.out.println(what + " ok");
        } catch (RuntimeException e) {
            System.out.println(what + " " + e);
        }
    }

    static String names(Thread[] list, int n) {
        StringBuilder b = new StringBuilder(n + ":");
        for (Thread t : Arrays.copyOf(list, n)) {
            b.append(' ').append(t.getName());
        }
        return b.toString();
    }

    static String names(ThreadGroup[] list, int n) {
        StringBuilder b = new StringBuilder(n + ":");
        for (ThreadGroup g : Arrays.copyOf(list, n)) {
            b.append(' ').append(g.getName());
        }
        return b.toString();
    }

    static void nulls(ThreadGroup main, ThreadGroup empty) {
        attempt("threads null", new Runnable() {
            @Override
            public void run() {
                main.enumerate((Thread[]) null);
            }
        });
        attempt("groups null", new Runnable() {
            @Override
            public void run() {
                main.enumerate((ThreadGroup[]) null);
            }
        });
        attempt("static null", new Runnable() {
            @Override
            public void run() {
                Thread.enumerate(null);
            }
        });
        attempt("empty threads null", new Runnable() {
            @Override
            public void run() {
                empty.enumerate((Thread[]) null, false);
            }
        });
        attempt("empty groups null", new Runnable() {
            @Override
            public void run() {
                empty.enumerate((ThreadGroup[]) null, false);
            }
        });
    }

    static void listings(ThreadGroup main, ThreadGroup g1) {
        Thread[] room = new Thread[10];
        System.out.println("all " + names(room, main.enumerate(room)));
        System.out.println("own " + names(room, main.enumerate(room, false)));
        System.out.println("g1 " + names(room, g1.enumerate(room)));
        System.out.println("g1 own " + names(room, g1.enumerate(room, false)));
        System.out.println("static " + names(room, Thread.enumerate(room)));
        Thread[] small = new Thread[3];
        System.out.println("small " + names(small, main.enumerate(small)));
        System.out.println("zero " + main.enumerate(new Thread[0]));
        try {
            main.enumerate(new Narrow[4]);
        } catch (ArrayStoreException e) {
            System.out.println("narrow array " + e);
        }
        Thread[] marked = new Thread[8];
        Arrays.fill(marked, Thread.currentThread());
        int n = g1.enumerate(marked);
        System.out.println("untouched past the end " + (marked[n] == Thread.currentThread()));

        ThreadGroup[] groups = new ThreadGroup[6];
        System.out.println("groups " + names(groups, main.enumerate(groups)));
        System.out.println("own groups " + names(groups, main.enumerate(groups, false)));
        ThreadGroup[] one = new ThreadGroup[1];
        System.out.println("one group " + names(one, main.enumerate(one)));
    }

    static void counts(String when, ThreadGroup main, ThreadGroup g1) {
        System.out.println(when + ": static " + Thread.activeCount() + ", main " + main.activeCount()
                + ", groups " + main.activeGroupCount() + ", g1 " + g1.activeCount()
                + ", g1 groups " + g1.activeGroupCount());
    }

    /** The ceiling goes down, and back up, through every subgroup. */
    static void ceilings() {
        ThreadGroup top = new ThreadGroup("top");
        ThreadGroup mid = new ThreadGroup(top, "mid");
        ThreadGroup low = new ThreadGroup(mid, "low");
        low.setMaxPriority(8);
        System.out.println("low only " + top.getMaxPriority() + " " + mid.getMaxPriority() + " " + low.getMaxPriority());
        top.setMaxPriority(3);
        System.out.println("down " + top.getMaxPriority() + " " + mid.getMaxPriority() + " " + low.getMaxPriority());
        mid.setMaxPriority(9);
        System.out.println("mid above top " + top.getMaxPriority() + " " + mid.getMaxPriority() + " " + low.getMaxPriority());
        top.setMaxPriority(9);
        System.out.println("up " + top.getMaxPriority() + " " + mid.getMaxPriority() + " " + low.getMaxPriority());
        top.setMaxPriority(0);
        top.setMaxPriority(11);
        System.out.println("out of range " + top.getMaxPriority() + " " + low.getMaxPriority());
        Thread t = new Thread(low, "t");
        top.setMaxPriority(1);
        System.out.println("thread keeps " + t.getPriority() + ", low " + low.getMaxPriority());
    }

    public static void main(String[] args) throws Exception {
        ceilings();
        ThreadGroup main = Thread.currentThread().getThreadGroup();
        counts("alone", main, new ThreadGroup("unused"));

        ThreadGroup g1 = new ThreadGroup("g1");
        ThreadGroup g2 = new ThreadGroup(g1, "g2");
        ThreadGroup g3 = new ThreadGroup("g3");
        Thread a1 = new Thread(g1, WAIT, "a1");
        Thread a2 = new Thread(g2, WAIT, "a2");
        Thread a3 = new Thread(g1, WAIT, "a3");
        Thread a4 = new Thread(g3, WAIT, "a4");
        Thread a5 = new Thread(WAIT, "a5");
        Thread never = new Thread(g1, WAIT, "never");
        counts("unstarted", main, g1);

        a5.start();
        a3.start();
        a2.start();
        a4.start();
        a1.start();
        counts("started", main, g1);
        listings(main, g1);
        nulls(main, new ThreadGroup("empty"));

        // The system group holds main's: its own extra threads differ by VM.
        ThreadGroup system = main.getParent();
        System.out.println("system parent " + system.getParent() + ", name " + system.getName());
        Thread[] everyone = new Thread[64];
        int n = system.enumerate(everyone);
        boolean found = false;
        for (int i = 0; i < n; i++) {
            found |= everyone[i] == a2;
        }
        System.out.println("a2 under system " + found);
        System.out.println("system own misses a2 " + !Arrays.asList(
                Arrays.copyOf(everyone, system.enumerate(everyone, false))).contains(a2));

        END.countDown();
        for (Thread t : new Thread[] {a1, a2, a3, a4, a5}) {
            t.join();
            System.out.println(t.getName() + " group after join " + t.getThreadGroup());
        }
        counts("ended", main, g1);
        Thread[] room = new Thread[4];
        System.out.println("left " + names(room, main.enumerate(room)));

        // A thread leaves the middle of the list, and the rest keep their order.
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(new Runnable() {
            @Override
            public void run() {
            }
        }, "first");
        Thread second = new Thread(WAITING(release), "second");
        Thread third = new Thread(WAITING(release), "third");
        second.start();
        first.start();
        third.start();
        first.join();
        System.out.println("gap closed " + names(room, Thread.enumerate(room)));
        release.countDown();
        second.join();
        third.join();

        try {
            first.start();
        } catch (IllegalThreadStateException e) {
            System.out.println("restart " + e);
        }
        System.out.println("after a failed restart " + Thread.activeCount());

        // More threads than a group starts with room for.
        CountDownLatch many = new CountDownLatch(1);
        Thread[] crowd = new Thread[20];
        for (int i = 0; i < crowd.length; i++) {
            crowd[i] = new Thread(WAITING(many), "c" + i);
            crowd[i].start();
        }
        Thread[] seen = new Thread[32];
        System.out.println("crowd " + names(seen, Thread.enumerate(seen)));
        many.countDown();
        for (Thread t : crowd) {
            t.join();
        }
        System.out.println("crowd gone " + Thread.activeCount());
        System.out.println("never " + never.getState() + " " + never.getThreadGroup().getName());
    }

    static Runnable WAITING(CountDownLatch latch) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    System.out.println(e);
                }
            }
        };
    }
}
