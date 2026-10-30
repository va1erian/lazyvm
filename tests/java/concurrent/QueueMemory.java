// VMARGS: -Xmx16m
package concurrent;

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
}
