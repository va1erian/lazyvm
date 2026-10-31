package lazy.internal;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * A key's equals writes an equal key, which opens its own op's claim, and then throws. Whoever stopped
 * behind that claim must not take it as proof that no earlier op on the key is running. A hand-written
 * golden: what such a recursive update does is undefined, and only this implementation has claims.
 */
public class ClaimOpenedThenThrown {
    static final ConcurrentHashMap<Key, Integer> map = new ConcurrentHashMap<>();
    static final CountDownLatch firstIn = new CountDownLatch(1);
    static final CountDownLatch firstGo = new CountDownLatch(1);
    static volatile boolean firstRunning;
    static volatile Key writing;
    static volatile Thread writer;
    static volatile Thread behind;
    static volatile String seenBehind = "never ran";

    static class Key {
        final int id;

        Key(int id) {
            this.id = id;
        }

        @Override public int hashCode() {
            return 7;
        }

        @Override public boolean equals(Object o) {
            if (this == writing && Thread.currentThread() == writer) {
                writing = null;
                map.compute(new Key(id), (k, v) -> 99);
                behind = daemon(() -> map.compute(this, (k, v) -> {
                    seenBehind = firstRunning ? "ran beside the first" : "ran after the first";
                    return 2;
                }));
                awaitBlocked(behind);
                throw new IllegalStateException("from equals");
            }
            return o instanceof Key && ((Key) o).id == id;
        }
    }

    static Thread unstarted(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    }

    static Thread daemon(Runnable r) {
        Thread t = unstarted(r);
        t.start();
        return t;
    }

    static void awaitBlocked(Thread t) {
        while (t.getState() != Thread.State.BLOCKED) {
            Thread.yield();
        }
    }

    public static void main(String[] args) throws Exception {
        Thread first = daemon(() -> map.compute(new Key(1), (k, v) -> {
            firstRunning = true;
            firstIn.countDown();
            try {
                firstGo.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            firstRunning = false;
            return 1;
        }));
        firstIn.await();

        Key key = new Key(1);
        writing = key;
        writer = unstarted(() -> {
            try {
                map.compute(key, (k, v) -> 5);
                System.out.println("the writer returned");
            } catch (IllegalStateException e) {
                System.out.println("the writer got: " + e.getMessage());
            }
        });
        writer.start();
        writer.join();

        // Now it must have moved on to the first op's claim, and stay there.
        behind.join(300);
        System.out.println("the one behind waits for the first: " + behind.isAlive());
        firstGo.countDown();
        first.join();
        behind.join();
        System.out.println("the one behind " + seenBehind);
        System.out.println("size " + map.size());
        manyBehind();
    }

    /** The same with no write in the equals, and several stopped behind: they must all get through. */
    static void manyBehind() throws Exception {
        map.clear();
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread other = daemon(() -> map.compute(new Key(2), (k, v) -> {
            in.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            return 1;
        }));
        in.await();
        Thread[] behindIt = new Thread[6];
        Key shared = new Key(1) {
            @Override public boolean equals(Object o) {
                if (Thread.currentThread() == writer) {
                    for (int i = 0; i < behindIt.length; i++) {
                        behindIt[i] = daemon(() -> map.merge(this, 1, Integer::sum));
                        awaitBlocked(behindIt[i]);
                    }
                    throw new IllegalStateException("from equals");
                }
                return o == this;
            }
        };
        writer = unstarted(() -> {
            try {
                map.compute(shared, (k, v) -> 5);
            } catch (IllegalStateException e) {
                System.out.println("many behind, the writer got: " + e.getMessage());
            }
        });
        writer.start();
        writer.join();
        go.countDown();
        other.join();
        for (Thread t : behindIt) {
            t.join(20000);
            if (t.isAlive()) {
                System.out.println("one stayed behind a claim nobody holds");
                return;
            }
        }
        System.out.println("many behind, sum " + map.get(shared));
    }
}
