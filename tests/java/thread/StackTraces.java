package thread;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Thread.getStackTrace of this thread and others, and Thread.getAllStackTraces. */
public class StackTraces {
    static void show(String what, StackTraceElement[] trace) {
        System.out.println(what + " (" + trace.length + ")");
        for (StackTraceElement e : trace) {
            // Line numbers only for this file: the class library's are its own.
            boolean mine = e.getClassName().startsWith("thread.");
            System.out.println("  " + e.getClassName() + "." + e.getMethodName()
                    + (e.isNativeMethod() ? " native" : "") + (mine ? ":" + e.getLineNumber() : ""));
        }
    }

    static void await(Thread t, Thread.State state) throws InterruptedException {
        while (t.getState() != state) {
            Thread.sleep(1);
        }
    }

    static class Sleeper extends Thread {
        Sleeper() {
            super("sleeper");
        }

        @Override
        public void run() {
            try {
                nap();
            } catch (InterruptedException e) {
                System.out.println("sleeper " + e.getMessage());
            }
        }

        void nap() throws InterruptedException {
            Thread.sleep(600000);
        }
    }

    static class Waiter implements Runnable {
        final Object lock = new Object();

        @Override
        public void run() {
            synchronized (lock) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    System.out.println("waiter " + e.getMessage());
                }
            }
        }
    }

    static class Blocker implements Runnable {
        final Object lock;

        Blocker(Object lock) {
            this.lock = lock;
        }

        @Override
        public void run() {
            synchronized (lock) {
                System.out.println("blocker in");
            }
        }
    }

    /** Blocks entering a synchronized method, static and not. */
    static class MethodBlocker implements Runnable {
        final boolean isStatic;

        MethodBlocker(boolean isStatic) {
            this.isStatic = isStatic;
        }

        @Override
        public void run() {
            if (isStatic) {
                lockedStatic();
            } else {
                locked();
            }
        }

        synchronized void locked() {
            System.out.println("locked");
        }

        static synchronized void lockedStatic() {
            System.out.println("locked static");
        }
    }

    static void blockedOnMethods() throws InterruptedException {
        MethodBlocker instance = new MethodBlocker(false);
        Thread t = new Thread(instance, "on method");
        synchronized (instance) {
            t.start();
            await(t, Thread.State.BLOCKED);
            show("blocked on method", t.getStackTrace());
        }
        t.join();
        Thread s = new Thread(new MethodBlocker(true), "on static");
        synchronized (MethodBlocker.class) {
            s.start();
            await(s, Thread.State.BLOCKED);
            show("blocked on static", s.getStackTrace());
        }
        s.join();
    }

    static StackTraceElement[] deeper(int n) {
        return n == 0 ? Thread.currentThread().getStackTrace() : deeper(n - 1);
    }

    static void ownTrace() {
        show("own", Thread.currentThread().getStackTrace());
        show("own deeper", deeper(2));
    }

    static void notRunning() throws InterruptedException {
        Thread unstarted = new Thread("unstarted");
        show("unstarted", unstarted.getStackTrace());
        Thread done = new Thread("done");
        done.start();
        done.join();
        show("ended", done.getStackTrace());
        System.out.println("unstarted in all " + Thread.getAllStackTraces().containsKey(unstarted));
        System.out.println("ended in all " + Thread.getAllStackTraces().containsKey(done));
    }

    /** main, seen from a thread of its own while it waits in join. */
    static void mainFromOutside() throws InterruptedException {
        Thread main = Thread.currentThread();
        Thread looker = new Thread("looker") {
            @Override
            public void run() {
                try {
                    await(main, State.WAITING);
                } catch (InterruptedException e) {
                    System.out.println(e);
                }
                show("main joining", main.getStackTrace());
            }
        };
        looker.start();
        looker.join();
    }

    static void running() throws InterruptedException {
        CountDownLatch going = new CountDownLatch(1);
        boolean[] stop = new boolean[1];
        Thread spinner = new Thread("spinner") {
            @Override
            public void run() {
                going.countDown();
                synchronized (stop) {
                    while (!stop[0]) {
                        try {
                            stop.wait(1);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }
        };
        spinner.start();
        going.await();
        // Wherever it is, it bottoms out in Thread.run and then its own run.
        StackTraceElement[] trace = spinner.getStackTrace();
        StackTraceElement base = trace[trace.length - 1];
        System.out.println("base " + base.getClassName() + "." + base.getMethodName());
        synchronized (stop) {
            stop[0] = true;
        }
        spinner.join();
    }

    public static void main(String[] args) throws Exception {
        ownTrace();
        notRunning();
        mainFromOutside();
        running();

        Sleeper sleeper = new Sleeper();
        sleeper.start();
        await(sleeper, Thread.State.TIMED_WAITING);
        show("sleeping", sleeper.getStackTrace());

        Waiter w = new Waiter();
        Thread waiter = new Thread(w, "waiter");
        waiter.start();
        await(waiter, Thread.State.WAITING);
        show("waiting", waiter.getStackTrace());

        Object held = new Object();
        Thread blocker = new Thread(new Blocker(held), "blocker");
        synchronized (held) {
            blocker.start();
            await(blocker, Thread.State.BLOCKED);
            show("blocked", blocker.getStackTrace());
        }
        blocker.join();
        blockedOnMethods();

        ThreadGroup grouped = new ThreadGroup("grouped");
        CountDownLatch end = new CountDownLatch(1);
        Thread inGroup = new Thread(grouped, new Runnable() {
            @Override
            public void run() {
                try {
                    end.await();
                } catch (InterruptedException e) {
                    System.out.println(e);
                }
            }
        }, "in group");
        inGroup.start();
        await(inGroup, Thread.State.WAITING);

        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        System.out.println("map " + all.getClass().getName());
        for (Thread t : new Thread[] {Thread.currentThread(), sleeper, waiter, inGroup}) {
            System.out.println(t.getName() + " in all " + all.containsKey(t));
        }
        show("sleeping in all", all.get(sleeper));
        show("waiting in all", all.get(waiter));
        StackTraceElement[] mine = all.get(Thread.currentThread());
        StackTraceElement last = mine[mine.length - 1];
        System.out.println("own in all ends " + last.getClassName() + "." + last.getMethodName());
        all.remove(sleeper);
        System.out.println("map is a copy " + Thread.getAllStackTraces().containsKey(sleeper));

        sleeper.interrupt();
        sleeper.join();
        waiter.interrupt();
        waiter.join();
        end.countDown();
        inGroup.join();
        System.out.println("traces of ended " + sleeper.getStackTrace().length);
    }
}
