package thread;

/** Interrupt status, and interrupting sleep, wait and join; thread states. */
public class Interrupts {
    static final Object lock = new Object();

    public static void main(String[] args) throws Exception {
        self();
        sleeping();
        waiting();
        joining();
        busy();
        notAlive();
        states();
        timedWait();
    }

    static void self() {
        Thread me = Thread.currentThread();
        System.out.println("initially " + me.isInterrupted());
        me.interrupt();
        System.out.println("isInterrupted keeps it: " + me.isInterrupted() + " " + me.isInterrupted());
        System.out.println("interrupted clears it: " + Thread.interrupted() + " " + Thread.interrupted());
        me.interrupt();
        try {
            Thread.sleep(1000);
            System.out.println("slept although interrupted");
        } catch (InterruptedException e) {
            System.out.println("pending interrupt stops sleep: " + e.getMessage() + ", now " + me.isInterrupted());
        }
        me.interrupt();
        synchronized (lock) {
            try {
                lock.wait(1000);
                System.out.println("waited although interrupted");
            } catch (InterruptedException e) {
                System.out.println("pending interrupt stops wait: " + e.getMessage() + ", now " + me.isInterrupted());
            }
            System.out.println("still holds the lock: " + Thread.holdsLock(lock));
        }
        try {
            Thread.sleep(0);
            Thread.sleep(1, 500);
            System.out.println("short sleeps return");
        } catch (InterruptedException e) {
            System.out.println("unexpected " + e);
        }
    }

    /** Waits until `t` is in state `s`, so the output does not depend on timing. */
    static void await(Thread t, Thread.State s) {
        while (t.getState() != s) {
            Thread.yield();
        }
    }

    static void sleeping() throws InterruptedException {
        final String[] out = new String[1];
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(60000);
                out[0] = "slept a minute";
            } catch (InterruptedException e) {
                out[0] = "sleep: " + e.getMessage() + ", flag " + Thread.currentThread().isInterrupted();
            }
        });
        t.start();
        await(t, Thread.State.TIMED_WAITING);
        t.interrupt();
        t.join();
        System.out.println(out[0]);
    }

    static void waiting() throws InterruptedException {
        final String[] out = new String[1];
        Thread t = new Thread(() -> {
            synchronized (lock) {
                try {
                    lock.wait();
                    out[0] = "woke without interrupt";
                } catch (InterruptedException e) {
                    out[0] = "wait: " + e.getMessage() + ", flag " + Thread.currentThread().isInterrupted()
                            + ", owns lock " + Thread.holdsLock(lock);
                }
            }
        });
        t.start();
        await(t, Thread.State.WAITING);
        // The waiter cannot leave wait() until it has the lock back.
        synchronized (lock) {
            t.interrupt();
            await(t, Thread.State.BLOCKED);
            System.out.println("interrupted waiter is blocked on the lock: " + t.getState());
        }
        t.join();
        System.out.println(out[0]);
    }

    static void joining() throws InterruptedException {
        final Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                System.out.println("sleeper interrupted last");
            }
        });
        sleeper.start();
        final String[] out = new String[1];
        Thread joiner = new Thread(() -> {
            try {
                sleeper.join();
                out[0] = "join returned";
            } catch (InterruptedException e) {
                out[0] = "join: " + e.getMessage();
            }
        });
        joiner.start();
        await(joiner, Thread.State.WAITING);
        joiner.interrupt();
        joiner.join();
        System.out.println(out[0] + ", sleeper alive " + sleeper.isAlive());
        sleeper.interrupt();
        sleeper.join();
    }

    static void busy() throws InterruptedException {
        final long[] spins = new long[1];
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                spins[0]++;
            }
        });
        t.start();
        while (spins[0] == 0) {
            Thread.yield();
        }
        t.interrupt();
        t.join();
        System.out.println("busy loop saw the flag: " + (spins[0] > 0));
    }

    static void notAlive() throws InterruptedException {
        Thread t = new Thread(() -> { });
        t.interrupt();
        System.out.println("unstarted: " + t.isInterrupted());
        t.start();
        t.join();
        t.interrupt();
        System.out.println("terminated: " + t.isInterrupted());
    }

    static void states() throws InterruptedException {
        final Object held = new Object();
        Thread t;
        synchronized (held) {
            t = new Thread(() -> {
                synchronized (held) {
                    System.out.println("got the lock at last");
                }
            });
            t.start();
            await(t, Thread.State.BLOCKED);
            System.out.println("contender: " + t.getState());
        }
        t.join();
        System.out.println("after: " + t.getState());
    }

    static void timedWait() throws InterruptedException {
        long start = System.nanoTime();
        synchronized (lock) {
            lock.wait(30);
        }
        long ms = (System.nanoTime() - start) / 1000000;
        System.out.println("timed wait returned on its own: " + (ms >= 25));
    }
}
