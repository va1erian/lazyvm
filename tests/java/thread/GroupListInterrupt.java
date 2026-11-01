package thread;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

/** ThreadGroup.list's layout and ThreadGroup.interrupt's reach. */
public class GroupListInterrupt {
    /** Waits for an interrupt, and reports whether it came. */
    static class Sleeper extends Thread {
        final CountDownLatch up = new CountDownLatch(1);
        volatile boolean hit;

        Sleeper(ThreadGroup g, String n) {
            super(g, n);
        }

        @Override
        public void run() {
            up.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                hit = true;
            }
        }

        Sleeper begin() throws Exception {
            start();
            up.await();
            return this;
        }
    }

    static void layout() throws Exception {
        ThreadGroup n = new ThreadGroup("n");
        ThreadGroup n1 = new ThreadGroup(n, "n1");
        n.setMaxPriority(7);
        new ThreadGroup(n, "n2");
        ThreadGroup deep = new ThreadGroup(n1, "deep");
        Sleeper a = new Sleeper(n, "a").begin();
        Sleeper b = new Sleeper(n1, "b").begin();
        Sleeper c = new Sleeper(n1, "c").begin();
        Sleeper d = new Sleeper(deep, "d").begin();
        Thread unstarted = new Thread(n, "unstarted");
        n.list();
        new ThreadGroup("empty").list();

        n.interrupt();
        for (Sleeper s : new Sleeper[] {a, b, c, d}) {
            s.join();
            System.out.println(s.getName() + " hit " + s.hit);
        }
        System.out.println("unstarted not hit " + !unstarted.isInterrupted());
        n.list();

        n1.destroy();
        n.list();
        ThreadGroup gone = new ThreadGroup("gone");
        gone.destroy();
        gone.list();
    }

    static void overrides() throws Exception {
        ThreadGroup o = new ThreadGroup("o") {
            @Override
            public String toString() {
                return "custom group " + getName();
            }
        };
        new ThreadGroup(o, "sub") {
            @Override
            public String toString() {
                return "custom sub";
            }

            @Override
            public void list() {
                System.out.println("list override");
                super.list();
            }
        };
        Sleeper t = new Sleeper(o, "t") {
            @Override
            public String toString() {
                return "custom thread";
            }

            @Override
            public void interrupt() {
                System.out.println("interrupt override");
                super.interrupt();
            }
        }.begin();
        o.list();
        o.interrupt();
        t.join();
        System.out.println("hit " + t.hit);
    }

    /** The indent is out before toString runs. */
    static void noisyToString() {
        ThreadGroup o = new ThreadGroup("noisy");
        new ThreadGroup(o, "n") {
            @Override
            public String toString() {
                System.out.println("[toString runs]");
                return "noisy";
            }
        };
        o.list();
        ThreadGroup t = new ThreadGroup("throwing");
        new ThreadGroup(t, "n") {
            @Override
            public String toString() {
                throw new IllegalStateException("no string");
            }
        };
        try {
            t.list();
        } catch (IllegalStateException e) {
            System.out.println(e);
        }
    }

    /** Subclass code that changes the group while list runs, and what list then shows. */
    static void reentrant() throws Exception {
        ThreadGroup top = new ThreadGroup("re");
        ThreadGroup doomed = new ThreadGroup(top, "doomed");
        CountDownLatch end = new CountDownLatch(1);
        Thread[] started = new Thread[1];
        ThreadGroup g = new ThreadGroup(top, "grows") {
            boolean once;

            @Override
            public String toString() {
                if (!once) {
                    once = true;
                    started[0] = new Thread(this, () -> await(end), "added by group");
                    started[0].start();
                    new ThreadGroup(this, "sub by group");
                    System.out.println("holds own " + Thread.holdsLock(this) + " parent " + Thread.holdsLock(top));
                }
                return "grows";
            }
        };
        new ThreadGroup(g, "first") {
            @Override
            public String toString() {
                System.out.println("sub holds own " + Thread.holdsLock(this) + " parent " + Thread.holdsLock(g));
                return "first";
            }
        };
        ThreadGroup killer = new ThreadGroup(top, "killer") {
            @Override
            public String toString() {
                if (!doomed.isDestroyed()) {
                    doomed.destroy();
                }
                return "killer";
            }
        };
        ThreadGroup late = new ThreadGroup(top, "late");
        top.list();
        late.destroy();
        new ThreadGroup(killer, "under").list();
        end.countDown();
        started[0].join();

        ThreadGroup h = new ThreadGroup("thread grows");
        ThreadGroup h1 = new ThreadGroup(h, "h1");
        ThreadGroup h2 = new ThreadGroup(h, "h2");
        CountDownLatch end2 = new CountDownLatch(1);
        Thread[] more = new Thread[1];
        Thread t = new Thread(h, () -> await(end2), "t") {
            @Override
            public String toString() {
                if (more[0] == null) {
                    more[0] = new Thread(h, () -> await(end2), "more");
                    more[0].start();
                    new ThreadGroup(h, "h3");
                    h1.destroy();
                    System.out.println("thread holds " + Thread.holdsLock(h));
                }
                return "t";
            }
        };
        t.start();
        h.list();
        h2.destroy();
        end2.countDown();
        t.join();
        more[0].join();
    }

    /** An interrupt override that adds a thread and a subgroup gets them interrupted too. */
    static void reentrantInterrupt() throws Exception {
        ThreadGroup g = new ThreadGroup("hits");
        Sleeper[] made = new Sleeper[2];
        Sleeper first = new Sleeper(g, "first") {
            @Override
            public void interrupt() {
                try {
                    if (made[0] == null) {
                        System.out.println("holds " + Thread.holdsLock(g));
                        made[0] = new Sleeper(g, "added").begin();
                        made[1] = new Sleeper(new ThreadGroup(g, "new"), "in new").begin();
                    }
                } catch (Exception e) {
                    System.out.println(e);
                }
                super.interrupt();
            }
        }.begin();
        g.interrupt();
        for (Sleeper s : new Sleeper[] {first, made[0], made[1]}) {
            s.join();
            System.out.println(s.getName() + " hit " + s.hit);
        }
    }

    /** All of a listing goes to the stream it began with, an indent being one print per space. */
    static void streams() throws Exception {
        PrintStream real = System.out;
        StringBuilder log = new StringBuilder();
        PrintStream first = new PrintStream(new ByteArrayOutputStream(), true) {
            @Override
            public void print(String s) {
                log.append("print[").append(s).append("]");
            }

            @Override
            public void println(Object o) {
                log.append("println[").append(o).append("]");
            }
        };
        ThreadGroup g = new ThreadGroup("switches") {
            @Override
            public String toString() {
                System.setOut(null);
                return "switches";
            }
        };
        new ThreadGroup(g, "sub");
        System.setOut(first);
        try {
            g.list();
        } finally {
            System.setOut(real);
        }
        System.out.println(log);
    }

    static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            System.out.println(e);
        }
    }

    /** It writes to whatever System.out is now. */
    static void redirected() {
        PrintStream real = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes, true));
        new ThreadGroup("caught").list();
        System.setOut(real);
        System.out.println("caught: " + bytes.toString().trim());
    }

    static void self() throws Exception {
        Thread t = new Thread(new ThreadGroup("si"), () -> {
            Thread.currentThread().getThreadGroup().interrupt();
            System.out.println("interrupts its caller " + Thread.interrupted());
        }, "self");
        t.start();
        t.join();
    }

    public static void main(String[] args) throws Exception {
        layout();
        overrides();
        noisyToString();
        reentrant();
        reentrantInterrupt();
        streams();
        redirected();
        self();
    }
}
