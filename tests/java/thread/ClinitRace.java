package thread;

/** Class initialization under threads (JVMS 5.5): once, waiting, failure, recursion. */
public class ClinitRace {
    static final int THREADS = 8;
    static int slowRuns;
    static int badRuns;

    static final class Slow {
        static final int VALUE;

        static {
            slowRuns++;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            VALUE = 42;
        }
    }

    static final class Bad {
        static int value;

        static {
            badRuns++;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (badRuns > 0) {
                throw new IllegalStateException("bad initializer");
            }
        }
    }

    static final class A {
        static int x = B.y + 1;
    }

    static final class B {
        static int y = A.x + 10;
    }

    /** Its initializer starts a thread that uses the class and waits for it. */
    static final class SelfStarter {
        static int value;
        static String seen;
        static String inInit;
        static Thread other;

        static {
            other = new Thread(() -> seen = "other thread saw " + value);
            other.start();
            try {
                other.join(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            inInit = "other thread still waiting: " + other.isAlive();
            value = 7;
        }
    }

    static final Object start = new Object();
    static boolean go;

    static void awaitStart() {
        synchronized (start) {
            while (!go) {
                try {
                    start.wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    static void release(Thread[] ts) throws InterruptedException {
        for (Thread t : ts) {
            t.start();
        }
        synchronized (start) {
            go = true;
            start.notifyAll();
        }
        for (Thread t : ts) {
            t.join();
        }
        go = false;
    }

    public static void main(String[] args) throws Exception {
        final int[] values = new int[THREADS];
        Thread[] ts = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int n = i;
            ts[i] = new Thread(() -> {
                awaitStart();
                values[n] = Slow.VALUE;
            });
        }
        release(ts);
        StringBuilder sb = new StringBuilder();
        for (int v : values) {
            sb.append(v).append(' ');
        }
        System.out.println("every thread saw: " + sb.toString().trim());
        System.out.println("Slow initialized " + slowRuns + " time(s)");

        final String[] errors = new String[THREADS];
        for (int i = 0; i < THREADS; i++) {
            final int n = i;
            ts[i] = new Thread(() -> {
                awaitStart();
                try {
                    errors[n] = "read " + Bad.value;
                } catch (Throwable e) {
                    errors[n] = e.getClass().getName() + ": " + e.getMessage();
                }
            });
        }
        release(ts);
        int eiie = 0;
        int ncdfe = 0;
        String ncdfeMessage = null;
        for (String e : errors) {
            if (e.startsWith("java.lang.ExceptionInInitializerError")) {
                eiie++;
            } else if (e.startsWith("java.lang.NoClassDefFoundError")) {
                ncdfe++;
                ncdfeMessage = e;
            } else {
                System.out.println("unexpected: " + e);
            }
        }
        System.out.println("ExceptionInInitializerError " + eiie + ", NoClassDefFoundError " + ncdfe);
        System.out.println(ncdfeMessage);
        System.out.println("Bad initializer ran " + badRuns + " time(s)");
        try {
            System.out.println(Bad.value);
        } catch (NoClassDefFoundError e) {
            System.out.println("later use: " + e.getMessage());
        }

        System.out.println("recursive: A.x=" + A.x + " B.y=" + B.y);
        System.out.println("SelfStarter.value=" + SelfStarter.value + ", " + SelfStarter.inInit);
        SelfStarter.other.join();
        System.out.println("then: " + SelfStarter.seen);
    }
}
