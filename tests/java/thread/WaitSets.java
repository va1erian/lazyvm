package thread;

/**
 * Wait sets: who a notify reaches, who leaves the set, and that wait()
 * lets blocked threads in. Every wait for another thread has a deadline,
 * so a broken VM prints a wrong line instead of hanging.
 */
public class WaitSets {
    interface Cond {
        boolean holds();
    }

    /** Waits up to 3 s for `c`; whether it came true. */
    static boolean eventually(Cond c) throws InterruptedException {
        long end = System.nanoTime() + 3000000000L;
        while (!c.holds()) {
            if (System.nanoTime() > end) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    static void awaitState(Thread t, Thread.State s) throws InterruptedException {
        eventually(() -> t.getState() == s);
    }

    /** A daemon that waits on `lock` and counts how it came back. */
    static Thread waiter(final Object lock, final int[] normal, final int[] interrupted,
            final boolean[] sawInterrupt, final int slot, final long timeout) {
        Thread t = new Thread(() -> {
            synchronized (lock) {
                try {
                    lock.wait(timeout);
                    normal[0]++;
                    if (sawInterrupt != null) {
                        sawInterrupt[slot] = Thread.currentThread().isInterrupted();
                    }
                } catch (InterruptedException e) {
                    interrupted[0]++;
                    if (sawInterrupt != null) {
                        sawInterrupt[slot] = true;
                    }
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    public static void main(String[] args) throws Exception {
        notifiedAndInterrupted();
        timedOutLeaves();
        interruptedLeaves();
        waitLetsEntrantsIn();
        pendingInterruptKeepsMonitor();
        notifyAllWakesAll();
        joinLoops();
    }

    /** JLS 17.2.4: a notify is never lost to an interrupt of the same waiter. */
    static void notifiedAndInterrupted() throws InterruptedException {
        final Object lock = new Object();
        final int[] normal = new int[1];
        final int[] interrupted = new int[1];
        final boolean[] saw = new boolean[2];
        Thread first = waiter(lock, normal, interrupted, saw, 0, 0);
        first.start();
        awaitState(first, Thread.State.WAITING);
        Thread second = waiter(lock, normal, interrupted, saw, 1, 0);
        second.start();
        awaitState(second, Thread.State.WAITING);
        synchronized (lock) {
            lock.notify();
            first.interrupt();
        }
        boolean one = eventually(() -> {
            synchronized (lock) {
                return normal[0] == 1 && (first.getState() == Thread.State.TERMINATED);
            }
        });
        System.out.println("notify plus interrupt: one normal return " + one
                + ", first waiter saw the interrupt " + saw[0]);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    /** A waiter that timed out is out of the set: the next notify reaches another. */
    static void timedOutLeaves() throws InterruptedException {
        final Object lock = new Object();
        final int[] normal = new int[1];
        final int[] interrupted = new int[1];
        Thread timed = waiter(lock, normal, interrupted, null, 0, 20);
        timed.start();
        timed.join();
        Thread second = waiter(lock, normal, interrupted, null, 0, 0);
        second.start();
        awaitState(second, Thread.State.WAITING);
        synchronized (lock) {
            lock.notify();
        }
        System.out.println("after a timeout, notify reaches the other waiter: "
                + eventually(() -> second.getState() == Thread.State.TERMINATED));
    }

    /** The same for a waiter that was interrupted. */
    static void interruptedLeaves() throws InterruptedException {
        final Object lock = new Object();
        final int[] normal = new int[1];
        final int[] interrupted = new int[1];
        Thread gone = waiter(lock, normal, interrupted, null, 0, 0);
        gone.start();
        awaitState(gone, Thread.State.WAITING);
        Thread second = waiter(lock, normal, interrupted, null, 0, 0);
        second.start();
        awaitState(second, Thread.State.WAITING);
        gone.interrupt();
        gone.join();
        synchronized (lock) {
            lock.notify();
        }
        System.out.println("after an interrupt, notify reaches the other waiter: "
                + eventually(() -> second.getState() == Thread.State.TERMINATED)
                + " (interrupted " + interrupted[0] + ")");
    }

    static volatile boolean entered;
    static volatile boolean ownerIn;
    static volatile boolean sneakedIn;

    /**
     * wait() gives the monitor up, so a thread blocked entering gets in. The
     * test only reads volatiles, so no other thread touches the lock.
     */
    static void waitLetsEntrantsIn() throws InterruptedException {
        final Object lock = new Object();
        final Thread[] entrant = new Thread[1];
        entrant[0] = new Thread(() -> {
            synchronized (lock) {
                entered = true;
                lock.notifyAll();
            }
        });
        entrant[0].setDaemon(true);
        Thread owner = new Thread(() -> {
            synchronized (lock) {
                ownerIn = true;
                try {
                    awaitState(entrant[0], Thread.State.BLOCKED);
                    if (!entered) {
                        lock.wait(5000);
                    }
                } catch (InterruptedException e) {
                    System.out.println("unexpected interrupt");
                }
            }
        });
        owner.start();
        eventually(() -> ownerIn);
        entrant[0].start();
        boolean in = eventually(() -> entered);
        owner.join();
        System.out.println("wait() let the blocked thread in: " + in);
    }

    /** A pending interrupt makes wait() throw without giving the monitor up. */
    static void pendingInterruptKeepsMonitor() throws InterruptedException {
        final Object lock = new Object();
        final boolean[] holdsAfter = new boolean[1];
        final Thread[] other = new Thread[1];
        other[0] = new Thread(() -> {
            synchronized (lock) {
                sneakedIn = true;
            }
        });
        Thread owner = new Thread(() -> {
            synchronized (lock) {
                try {
                    other[0].start();
                    awaitState(other[0], Thread.State.BLOCKED);
                    // Long enough that a release would hand the monitor over.
                    Thread.sleep(30);
                    Thread.currentThread().interrupt();
                    lock.wait();
                } catch (InterruptedException e) {
                    holdsAfter[0] = !sneakedIn && Thread.holdsLock(lock);
                }
            }
        });
        owner.start();
        owner.join();
        other[0].join();
        System.out.println("pending interrupt: wait threw keeping the monitor " + holdsAfter[0]);
    }

    static void notifyAllWakesAll() throws InterruptedException {
        final Object lock = new Object();
        final int[] normal = new int[1];
        final int[] interrupted = new int[1];
        Thread[] ts = new Thread[3];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = waiter(lock, normal, interrupted, null, 0, 0);
            ts[i].start();
            awaitState(ts[i], Thread.State.WAITING);
        }
        synchronized (lock) {
            lock.notifyAll();
        }
        System.out.println("notifyAll woke every waiter: " + eventually(() -> {
            synchronized (lock) {
                return normal[0] == ts.length;
            }
        }));
    }

    /** join keeps waiting through a notifyAll on the thread that is not its end. */
    static void joinLoops() throws InterruptedException {
        final boolean[] finish = new boolean[1];
        final Thread worker = new Thread(() -> {
            while (true) {
                synchronized (finish) {
                    if (finish[0]) {
                        return;
                    }
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        worker.start();
        final Thread main = Thread.currentThread();
        Thread poker = new Thread(() -> {
            try {
                awaitState(main, Thread.State.WAITING);
                synchronized (worker) {
                    worker.notifyAll();
                }
                Thread.sleep(50);
            } catch (InterruptedException e) {
                System.out.println("unexpected interrupt");
            }
            synchronized (finish) {
                finish[0] = true;
            }
        });
        poker.start();
        worker.join();
        System.out.println("join returned only at the end: " + !worker.isAlive());
        poker.join();
    }
}
