package thread;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Busy threads must not stretch every handover of a contended lock. A thread
 * that blocks on a lock has to get the VM back soon: if each handover waited
 * behind every spinning thread, this would take minutes, not a moment, and the
 * test's time limit would catch it.
 */
public class SpinnerConvoy {
    static final int SPINNERS = 3;
    static final int WORKERS = 3;
    static final int OPS = 5000;
    static final int ROUNDS = 4;
    static volatile boolean spin = true;

    interface Job {
        void run() throws Exception;
    }

    static void everyWorker(Job job) throws Exception {
        final int[] failures = {0};
        Thread[] ts = new Thread[WORKERS];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(() -> {
                try {
                    job.run();
                } catch (Exception e) {
                    synchronized (failures) {
                        failures[0]++;
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        if (failures[0] != 0) {
            System.out.println("failures: " + failures[0]);
        }
    }

    static void queue() throws Exception {
        final LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>();
        final long[] sum = {0};
        everyWorker(() -> {
            for (int j = 0; j < OPS; j++) {
                q.offer(j);
                Integer got = q.poll();
                if (got != null) {
                    synchronized (sum) {
                        sum[0] += got;
                    }
                }
            }
        });
        System.out.println("queue: left " + q.size() + ", sum " + sum[0]);
    }

    /** Taking blocks: each item goes to a thread parked on the queue. */
    static void pingPong() throws Exception {
        final LinkedBlockingQueue<Integer> there = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Integer> back = new LinkedBlockingQueue<>();
        Thread echo = new Thread(() -> {
            try {
                for (int j = 0; j < OPS / 10; j++) {
                    back.put(there.take() + 1);
                }
            } catch (InterruptedException e) {
                back.offer(-1);
            }
        });
        echo.start();
        long sum = 0;
        for (int j = 0; j < OPS / 10; j++) {
            there.put(j);
            sum += back.take();
        }
        echo.join();
        System.out.println("ping-pong: sum " + sum);
    }

    static void monitorHandoffs() throws Exception {
        final Object lock = new Object();
        final int[] state = {0, 0};
        everyWorker(() -> {
            for (int j = 0; j < OPS; j++) {
                synchronized (lock) {
                    state[0]++;
                    lock.notifyAll();
                }
            }
        });
        System.out.println("monitor: " + state[0]);
    }

    static void lockAndCondition() throws Exception {
        final ReentrantLock lock = new ReentrantLock();
        final Condition nonEmpty = lock.newCondition();
        final int[] items = {0, 0};
        final int total = WORKERS * OPS / 10;
        Thread consumer = new Thread(() -> {
            lock.lock();
            try {
                while (items[1] < total) {
                    while (items[0] == 0) {
                        nonEmpty.awaitUninterruptibly();
                    }
                    items[0]--;
                    items[1]++;
                }
            } finally {
                lock.unlock();
            }
        });
        consumer.start();
        everyWorker(() -> {
            for (int j = 0; j < OPS / 10; j++) {
                lock.lock();
                try {
                    items[0]++;
                    nonEmpty.signal();
                } finally {
                    lock.unlock();
                }
            }
        });
        consumer.join();
        System.out.println("condition: " + items[1]);
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < SPINNERS; i++) {
            Thread t = new Thread(() -> {
                int k = 0;
                while (spin) {
                    k++;
                }
            });
            t.setDaemon(true);
            t.start();
        }
        for (int round = 0; round < ROUNDS; round++) {
            monitorHandoffs();
            queue();
            pingPong();
            lockAndCondition();
        }
        spin = false;
    }
}
