package thread;

/**
 * Threads looping on one contended lock all get it: a thread that leaves
 * the monitor and comes straight back must not keep it from the others.
 */
public class Fairness {
    static final Object lock = new Object();
    static volatile boolean stop;

    static long work(long x) {
        for (int i = 0; i < 2000; i++) {
            x = x * 31 + i;
        }
        return x;
    }

    public static void main(String[] args) throws Exception {
        final long[] counts = new long[3];
        Thread[] ts = new Thread[counts.length];
        for (int k = 0; k < ts.length; k++) {
            final int id = k;
            ts[k] = new Thread(() -> {
                long x = 0;
                while (!stop) {
                    synchronized (lock) {
                        x = work(x);
                        counts[id]++;
                    }
                }
            });
            ts[k].start();
        }
        Thread.sleep(1500);
        stop = true;
        for (Thread t : ts) {
            t.join();
        }
        long min = Long.MAX_VALUE;
        for (long c : counts) {
            min = Math.min(min, c);
        }
        // A loose bound: a fair lock gives each thread thousands of turns.
        System.out.println("every thread made progress: " + (min >= 10));
    }
}
