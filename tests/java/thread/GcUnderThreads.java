package thread;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Eight threads allocate for 1000 iterations each while collections run.
 * Each keeps a structure alive across the whole run and checks it every
 * iteration, so a collector that missed a thread's frames would show.
 */
public class GcUnderThreads {
    static final int THREADS = 8;
    static final int ITERATIONS = 1000;
    static final long[] results = new long[THREADS];
    static final String[] errors = new String[THREADS];

    static final class Node {
        final int value;
        final Node next;
        final int[] payload;

        Node(int value, Node next) {
            this.value = value;
            this.next = next;
            this.payload = new int[16];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = value * 31 + i;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        long collections = 0;
        Thread[] ts = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            ts[t] = new Thread(() -> work(id), "gc-" + t);
        }
        for (Thread t : ts) {
            t.start();
        }
        // The main thread collects too, while the workers run.
        WeakReference<Object> probe = new WeakReference<Object>(new Object());
        while (anyAlive(ts)) {
            System.gc();
            collections++;
            Thread.sleep(1);
        }
        for (Thread t : ts) {
            t.join();
        }
        System.gc();
        for (int t = 0; t < THREADS; t++) {
            System.out.println("thread " + t + ": " + (errors[t] != null ? errors[t] : "checksum " + results[t]));
        }
        System.out.println("main collected while workers ran: " + (collections > 0));
        System.out.println("weak probe cleared: " + (probe.get() == null));
    }

    static boolean anyAlive(Thread[] ts) {
        for (Thread t : ts) {
            if (t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    static void work(int id) {
        // Lives for the whole run, held only by this thread's frame.
        Node keep = null;
        for (int i = 0; i < 64; i++) {
            keep = new Node(id * 1000 + i, keep);
        }
        long sum = 0;
        for (int it = 0; it < ITERATIONS; it++) {
            List<Object> garbage = new ArrayList<Object>();
            for (int k = 0; k < 40; k++) {
                garbage.add(new int[32]);
                garbage.add("s" + id + "-" + k);
            }
            Node chain = null;
            for (int k = 0; k < 20; k++) {
                chain = new Node(k + it, chain);
            }
            for (Node n = chain; n != null; n = n.next) {
                sum += n.payload[3];
            }
            sum += garbage.size();
            if (it % 100 == 0) {
                System.gc();
            }
            String bad = check(keep, id);
            if (bad != null) {
                errors[id] = "corrupt at iteration " + it + ": " + bad;
                return;
            }
        }
        results[id] = sum;
    }

    static String check(Node keep, int id) {
        int expect = id * 1000 + 63;
        for (Node n = keep; n != null; n = n.next, expect--) {
            if (n.value != expect) {
                return "value " + n.value + " != " + expect;
            }
            for (int i = 0; i < n.payload.length; i++) {
                if (n.payload[i] != n.value * 31 + i) {
                    return "payload of " + n.value;
                }
            }
        }
        return expect == id * 1000 - 1 ? null : "short chain";
    }
}
