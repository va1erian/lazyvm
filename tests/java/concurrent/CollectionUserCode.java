package concurrent;

import java.util.*;
import java.util.concurrent.*;

/**
 * A key's equals or compareTo is user code, and may block. Every step here is ordered by latches or by
 * polling a thread's state: one thread stops inside the user code, another then acts on the collection.
 */
public class CollectionUserCode {
    interface Op { Object run() throws Exception; }

    static CountDownLatch inside;
    static CountDownLatch release;

    static void pause() {
        inside.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    static class Key {
        final int v;
        final int h;
        Key(int v, int h) { this.v = v; this.h = h; }
        @Override public int hashCode() { return h; }
        @Override public boolean equals(Object o) { return o instanceof Key && ((Key) o).v == v; }
        @Override public String toString() { return "K" + v; }
    }

    /** Stops in equals, whichever key it is compared with. */
    static final class StopsInEquals extends Key {
        StopsInEquals(int v, int h) { super(v, h); }
        @Override public boolean equals(Object o) { pause(); return super.equals(o); }
    }

    static final class Ranked implements Comparable<Ranked> {
        final int v;
        final boolean stops;
        Ranked(int v, boolean stops) { this.v = v; this.stops = stops; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof Ranked && ((Ranked) o).v == v; }
        @Override public int compareTo(Ranked o) {
            if (stops) pause();
            return Integer.compare(v, o.v);
        }
    }

    static Thread start(Op op, Object[] result) {
        Thread t = new Thread(() -> {
            try {
                result[0] = op.run();
            } catch (Exception e) {
                result[0] = e.getClass().getSimpleName();
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Whether {@code other} can finish while {@code stuck} sits in user code; then what each answered. */
    static void during(String label, Op stuck, Op other) throws Exception {
        inside = new CountDownLatch(1);
        release = new CountDownLatch(1);
        Object[] stuckResult = new Object[1];
        Object[] otherResult = new Object[1];
        Thread s = start(stuck, stuckResult);
        if (!inside.await(30, TimeUnit.SECONDS)) throw new AssertionError(label + ": no user code reached");
        Thread o = start(other, otherResult);
        o.join(10000);
        boolean ran = !o.isAlive();
        release.countDown();
        s.join();
        o.join();
        System.out.println(label + " = " + (ran ? "ran" : "stuck") + ", answers " + stuckResult[0] + " after " + otherResult[0]);
    }

    /** {@code stuck} sits in user code while {@code other} either finishes or blocks; only the end state is told. */
    static void racing(Op stuck, Op other) throws Exception {
        inside = new CountDownLatch(1);
        release = new CountDownLatch(1);
        Thread s = start(stuck, new Object[1]);
        if (!inside.await(30, TimeUnit.SECONDS)) throw new AssertionError("no user code reached");
        Thread o = start(other, new Object[1]);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (o.isAlive() && o.getState() != Thread.State.BLOCKED && o.getState() != Thread.State.WAITING) {
            if (System.nanoTime() > end) throw new AssertionError("other neither ended nor blocked");
            Thread.sleep(1);
        }
        release.countDown();
        s.join();
        o.join();
    }

    static void otherBinWrites() throws Exception {
        ConcurrentHashMap<Object, Object> m = new ConcurrentHashMap<>();
        m.put(new Key(1, 1), "a");
        during("map put, equals stopped, write to another bin", () -> m.put(new StopsInEquals(1, 1), "b"), () -> m.put(new Key(3, 3), "c"));
        during("map remove, equals stopped, write to another bin", () -> m.remove(new StopsInEquals(1, 1)), () -> m.put(new Key(4, 4), "c"));
        m.put(new Key(1, 1), "a");
        Object stopsAsValue = new Object() {
            @Override public boolean equals(Object o) { pause(); return false; }
        };
        during("map replace, value equals stopped, write to another bin", () -> m.replace(new Key(1, 1), stopsAsValue, "x"), () -> m.put(new Key(5, 5), "c"));
        during("map remove pair, value equals stopped, write to another bin", () -> m.remove(new Key(1, 1), stopsAsValue), () -> m.put(new Key(6, 6), "c"));
        during("map remove pair, value equals stopped, read", () -> m.remove(new Key(1, 1), stopsAsValue), () -> m.get(new Key(1, 1)));
        show("map after", () -> m.size());
    }

    /** The owner's shape: one thread holds a lock and writes, another writes with a key whose equals takes that lock. */
    static void lockInEquals() throws Exception {
        Object lock = new Object();
        final class Locking extends Key {
            Locking(int v, int h) { super(v, h); }
            @Override public boolean equals(Object o) {
                synchronized (lock) {
                    return super.equals(o);
                }
            }
        }
        ConcurrentHashMap<Object, Integer> m = new ConcurrentHashMap<>();
        m.put(new Key(1, 1), 0);
        m.put(new Key(2, 2), 0);
        Object[] answer = new Object[1];
        Thread writer;
        synchronized (lock) {
            writer = start(() -> m.put(new Locking(1, 1), 7), answer);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (writer.getState() != Thread.State.BLOCKED) {
                if (System.nanoTime() > end) throw new AssertionError("writer never reached the lock");
                Thread.sleep(1);
            }
            Object[] mine = new Object[1];
            Thread second = start(() -> m.put(new Key(2, 2), 8), mine);
            second.join(10000);
            show("write while another writer waits for this thread's lock", () -> second.isAlive() ? "stuck" : "ran, old " + mine[0]);
        }
        writer.join();
        show("the waiting writer then", () -> answer[0] + " " + m.get(new Key(1, 1)));
    }

    /** A long bin: the same for compareTo. */
    static void longBin() throws Exception {
        ConcurrentHashMap<Object, Object> m = new ConcurrentHashMap<>();
        for (int i = 0; i < 40; i++) m.put(new Ranked(i, false), i);
        during("long bin put, compareTo stopped, write to another bin", () -> m.put(new Ranked(100, true), "n"), () -> m.put(new Key(3, 3), "c"));
        during("long bin get, compareTo stopped, write to another bin", () -> m.get(new Ranked(101, true)), () -> m.put(new Key(4, 4), "c"));
        during("long bin remove, compareTo stopped, write to another bin", () -> m.remove(new Ranked(102, true)), () -> m.put(new Key(5, 5), "c"));
        show("long bin after", m::size);
    }

    /** A search has to stand when the write comes: the same key from elsewhere must not end up twice. */
    static void sameBin() throws Exception {
        ConcurrentHashMap<Object, Object> m = new ConcurrentHashMap<>();
        m.put(new Key(1, 1), "a");
        racing(() -> m.put(new StopsInEquals(2, 1), "x"), () -> m.put(new Key(2, 1), "y"));
        show("put against put of the same key in one bin", m::size);
        racing(() -> m.remove(new StopsInEquals(2, 1)), () -> m.remove(new Key(2, 1)));
        show("remove against remove of the same key", m::size);
        racing(() -> m.put(new StopsInEquals(2, 1), "x"), () -> m.remove(new Key(1, 1)));
        show("put against a remove of the key it was comparing with", () -> m.size() + " " + m.containsKey(new Key(2, 1)) + " " + m.containsKey(new Key(1, 1)));

        ConcurrentHashMap<Object, Object> tree = new ConcurrentHashMap<>();
        for (int i = 0; i < 40; i++) tree.put(new Ranked(i, false), i);
        racing(() -> tree.put(new Ranked(100, true), "x"), () -> tree.put(new Ranked(100, false), "y"));
        show("long bin: put against put of the same key", tree::size);
        racing(() -> tree.put(new Ranked(101, true), "x"), () -> tree.remove(new Ranked(5, false)));
        show("long bin: put against a remove", () -> tree.size() + " " + tree.containsKey(new Ranked(101, false)) + " " + tree.containsKey(new Ranked(5, false)));
        racing(() -> tree.remove(new Ranked(7, true)), () -> tree.put(new Ranked(102, false), "y"));
        show("long bin: remove against a put", () -> tree.size() + " " + tree.containsKey(new Ranked(7, false)) + " " + tree.containsKey(new Ranked(102, false)));
    }

    static void linkedQueues() throws Exception {
        for (Queue<Key> q : Arrays.<Queue<Key>>asList(new ConcurrentLinkedQueue<>(), new ConcurrentLinkedDeque<>(), new LinkedTransferQueue<>())) {
            String name = q.getClass().getSimpleName();
            q.add(new Key(1, 1));
            during(name + " contains, equals stopped, offer", () -> q.contains(new StopsInEquals(1, 1)), () -> q.offer(new Key(2, 2)));
            during(name + " remove, equals stopped, poll", () -> q.remove(new StopsInEquals(2, 2)), () -> q.poll().v);
            show(name + " after", () -> q);
            q.add(new Key(3, 3));
            during(name + " remove, equals stopped, that element polled", () -> q.remove(new StopsInEquals(3, 3)), () -> q.poll().v);
            show(name + " after that", () -> q);
            q.clear();
            q.add(new Key(4, 4));
            q.add(new Key(5, 5));
            during(name + " remove of the second, equals stopped at the first, removed by another", () -> q.remove(new StopsInEquals(5, 5)), () -> q.remove(new Key(5, 5)));
            show(name + " left", () -> q);
            q.clear();
        }
    }

    static void copyOnWrite() throws Exception {
        CopyOnWriteArrayList<Key> l = new CopyOnWriteArrayList<>();
        l.add(new Key(1, 1));
        during("list addIfAbsent, equals stopped, add", () -> l.addIfAbsent(new StopsInEquals(2, 2)), () -> l.add(new Key(3, 3)));
        show("list after", () -> l);
        during("list addIfAbsent, equals stopped, the same key added", () -> l.addIfAbsent(new StopsInEquals(4, 4)), () -> l.add(new Key(4, 4)));
        show("list after same key", () -> l);
        during("list remove, equals stopped, add", () -> l.remove(new StopsInEquals(3, 3)), () -> l.add(new Key(5, 5)));
        show("list after remove", () -> l);
        during("list remove, equals stopped, that element removed", () -> l.remove(new StopsInEquals(5, 5)), () -> l.remove(new Key(5, 5)));
        show("list after lost remove", () -> l);

        CopyOnWriteArraySet<Key> set = new CopyOnWriteArraySet<>();
        set.add(new Key(1, 1));
        during("set add, equals stopped, add", () -> set.add(new StopsInEquals(2, 2)), () -> set.add(new Key(3, 3)));
        show("set after", () -> set);

        CopyOnWriteArrayList<Key> parent = new CopyOnWriteArrayList<>(Arrays.asList(new Key(1, 1), new Key(2, 2)));
        List<Key> sub = parent.subList(0, 2);
        during("sub-list remove, equals stopped, parent written", () -> sub.remove(new StopsInEquals(1, 1)), () -> parent.add(new Key(3, 3)));
        show("parent after", () -> parent);
    }

    static void show(String label, Op op) throws Exception {
        System.out.println(label + " = " + op.run());
    }

    public static void main(String[] args) throws Exception {
        otherBinWrites();
        lockInEquals();
        longBin();
        sameBin();
        linkedQueues();
        copyOnWrite();
    }
}
