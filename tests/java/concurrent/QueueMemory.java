// VMARGS: -Xmx16m
package concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** The heap runs out at the moment a thread joins a queue: those already in it must not suffer. */
public class QueueMemory {
    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void waiting(Thread t) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (t.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > end) throw new AssertionError("stuck");
            Thread.sleep(1);
        }
    }

    /** Takes everything down to blocks of `floor` bytes. */
    static List<Object> fill(int floor) {
        List<Object> hog = new ArrayList<>(100_000);
        for (int size = 1 << 20; size >= floor; size >>= 1) {
            try {
                while (true) hog.add(new byte[size]);
            } catch (OutOfMemoryError e) {
                // On to smaller pieces.
            }
        }
        return hog;
    }

    public static void main(String[] args) throws Exception {
        warm();
        joinWithNoMemory(16);
        joinWithNoMemory(64);
        joinWithNoMemory(256);
        dequeWithNoMemory();
        System.exit(0);
    }

    /** Whatever the scenarios need is loaded while there is room. */
    static void warm() throws Exception {
        ReentrantLock l = new ReentrantLock(true);
        l.lock();
        Thread t = start(() -> { l.lock(); l.unlock(); });
        waiting(t);
        l.unlock();
        t.join();
        new ArrayDeque<Object>().addLast("");
        try { throw new OutOfMemoryError("warm"); } catch (OutOfMemoryError e) { }
    }

    /** 15 queued, which is when a 16-slot array would have to grow; a sixteenth joins with no heap. */
    static void joinWithNoMemory(int floor) throws Exception {
        ReentrantLock l = new ReentrantLock(true);
        l.lock();
        List<Thread> queued = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            Thread t = start(() -> { l.lock(); l.unlock(); });
            waiting(t);
            queued.add(t);
        }
        Thread newcomer = new Thread(() -> {
            List<Object> hog = fill(floor);
            try {
                l.lock();
                l.unlock();
            } catch (OutOfMemoryError e) {
                // Either is fine: what counts is who comes through.
            }
            hog.clear();
        });
        newcomer.start();
        for (int i = 0; i < 300 && newcomer.isAlive() && l.getQueueLength() < 16; i++) Thread.sleep(10);
        l.unlock();
        newcomer.join(20_000);
        int stuck = newcomer.isAlive() ? 1 : 0;
        for (Thread t : queued) {
            t.join(20_000);
            if (t.isAlive()) stuck++;
        }
        boolean usable = l.tryLock(20, TimeUnit.SECONDS);
        System.out.println("floor " + floor + ": stuck " + stuck + " usable " + usable + " queue " + l.getQueueLength());
    }

    /** One add fails for want of a bigger array; the deque must still work. */
    static void dequeWithNoMemory() {
        ArrayDeque<Integer> d = new ArrayDeque<>(4000);
        for (int i = 0; i < 1000; i++) d.addLast(i);
        List<Object> hog = fill(16);
        int added = 0;
        boolean ran = false;
        try {
            for (int i = 1000; i < 200_000; i++) {
                d.addLast(1000);
                added++;
            }
        } catch (OutOfMemoryError e) {
            ran = true;
        }
        hog.clear();
        hog = null;
        d.addLast(-1);
        d.addFirst(-2);
        int count = 0;
        int last = 0;
        for (int x : d) {
            count++;
            last = x;
        }
        System.out.println("deque ran out " + ran + ", first " + d.peekFirst() + " last " + last
            + " in step " + (count == d.size()) + " grown " + (d.size() >= 1002));
    }
}
