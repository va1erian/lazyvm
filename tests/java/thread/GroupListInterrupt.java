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
        redirected();
        self();
    }
}
