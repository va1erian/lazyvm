package concurrent;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * What a running compute may meet on ConcurrentHashMap, where one thread waits for another's claim while
 * the first updates the same map from inside its function. What a recursive update leaves behind is not
 * defined, so most lines say only that everything finished. Threads are stopped by latches and by the
 * state of the thread queued, so no line rests on timing; a bound on time only turns a hang into a line.
 */
public class CollectionClaims {
    static final int LONG_ENOUGH_MS = 30000;

    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static void stopped(Thread t) throws InterruptedException {
        for (;;) {
            Thread.State s = t.getState();
            if (s == Thread.State.BLOCKED || s == Thread.State.WAITING || s == Thread.State.TERMINATED) {
                return;
            }
            Thread.sleep(1);
        }
    }

    /** {@code inner} runs inside compute("k"), once {@code queued}, another thread's update of "k", has stopped behind it. */
    static ConcurrentHashMap<String, Integer> nested(String label, Consumer<ConcurrentHashMap<String, Integer>> inner, Consumer<ConcurrentHashMap<String, Integer>> queued) throws Exception {
        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        m.put("k", 1);
        m.put("j", 1);
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread outer = start(() -> m.compute("k", (k, v) -> {
            in.countDown();
            await(go);
            inner.accept(m);
            return 100;
        }));
        await(in);
        Thread second = start(() -> queued.accept(m));
        stopped(second);
        go.countDown();
        outer.join(LONG_ENOUGH_MS);
        second.join(LONG_ENOUGH_MS);
        System.out.println(label + ": " + (outer.isAlive() || second.isAlive() ? "STUCK" : "finished"));
        return m;
    }

    static void recursion() throws Exception {
        Consumer<ConcurrentHashMap<String, Integer>> put = m -> m.put("k", 2);
        Consumer<ConcurrentHashMap<String, Integer>> compute = m -> m.compute("k", (k, v) -> 9);
        nested("put of that key inside compute, a put queued", m -> m.put("k", 50), put);
        nested("remove(k, v) of that key inside compute, a put queued", m -> m.remove("k", 99), put);
        nested("remove of that key inside compute, a put queued", m -> m.remove("k"), put);
        nested("replace of that key inside compute, a put queued", m -> m.replace("k", 77), put);
        nested("replace(k, v, v) of that key inside compute, a put queued", m -> m.replace("k", 1, 77), put);
        nested("computeIfAbsent of that key inside compute, a put queued", m -> m.computeIfAbsent("k", k -> 5), put);
        nested("putIfAbsent of that key inside compute, a put queued", m -> m.putIfAbsent("k", 5), put);
        nested("put of another key inside compute, a put queued", m -> m.put("j", 5), put);
        nested("put of that key inside compute, a compute queued", m -> m.put("k", 50), compute);
        nested("entry setValue of that key inside compute, a put queued", m -> {
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (e.getKey().equals("k")) {
                    e.setValue(5);
                }
            }
        }, put);
        nested("iterator remove of that key inside compute, a put queued", m -> {
            for (Iterator<String> i = m.keySet().iterator(); i.hasNext();) {
                if (i.next().equals("k")) {
                    i.remove();
                }
            }
        }, put);
        // The clear takes the entry its compute found, so the queued write is all that stays.
        System.out.println("  end " + new TreeMap<>(nested("clear inside compute, a put queued", ConcurrentHashMap::clear, put)));
        System.out.println("  end " + new TreeMap<>(nested("keySet clear inside compute, a compute queued", m -> m.keySet().clear(), compute)));
    }

    /** A value whose {@code equals} takes a while. */
    static final class Slow {
        final int id;

        Slow(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return o instanceof Slow && ((Slow) o).id == id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    /** It must end while the writer goes on: the writer only stops when {@code op} has ended, or after a long time. */
    static void againstWriter(String label, BiFunction<ConcurrentHashMap<String, Slow>, Slow, Object> op) throws Exception {
        ConcurrentHashMap<String, Slow> m = new ConcurrentHashMap<>();
        m.put("k", new Slow(1));
        boolean[] stop = {false};
        Thread writer = start(() -> {
            while (!stop[0]) {
                m.put("k", new Slow(1));
            }
        });
        Object[] answer = {"none"};
        Thread t = start(() -> answer[0] = op.apply(m, new Slow(1)));
        t.join(LONG_ENOUGH_MS);
        System.out.println(label + ": " + (t.isAlive() ? "still going" : "ended, " + answer[0]));
        stop[0] = true;
        t.join();
        writer.join();
    }

    /** A key whose calls from the map are counted. */
    static final class Counted {
        static int equalsCalls;
        static int hashCalls;
        final int id;

        Counted(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            equalsCalls++;
            return o instanceof Counted && ((Counted) o).id == id;
        }

        @Override
        public int hashCode() {
            hashCalls++;
            return id;
        }

        static String counts() {
            String out = "equals " + equalsCalls + ", hashCode " + hashCalls;
            equalsCalls = 0;
            hashCalls = 0;
            return out;
        }
    }

    /** A write is not made dearer by a compute it does not collide with. */
    static void besideACompute() throws Exception {
        ConcurrentHashMap<Object, Integer> m = new ConcurrentHashMap<>();
        m.put(new Counted(1), 1);
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread other = start(() -> m.compute("elsewhere", (k, v) -> {
            in.countDown();
            await(go);
            return 1;
        }));
        await(in);
        Counted.counts();
        m.put(new Counted(1), 2);
        System.out.println("put of a present key beside another compute: " + Counted.counts());
        m.remove(new Counted(1));
        System.out.println("remove of a present key beside another compute: " + Counted.counts());
        go.countDown();
        other.join();
    }

    /** Two keys of one hash, whose {@code equals} throws when told to. */
    static final class Throwing {
        static boolean armed;
        final int id;

        Throwing(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (armed) {
                throw new IllegalStateException("equals");
            }
            return o instanceof Throwing && ((Throwing) o).id == id;
        }

        @Override
        public int hashCode() {
            return 7;
        }
    }

    /** It reaches the writer, here or after the wait, and leaves nothing behind that stops the next writer. */
    static void equalsThrowsBesideACompute() throws Exception {
        ConcurrentHashMap<Throwing, Integer> m = new ConcurrentHashMap<>();
        Throwing a = new Throwing(1);
        Throwing b = new Throwing(2);
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread computing = start(() -> m.compute(a, (k, v) -> {
            in.countDown();
            await(go);
            return 1;
        }));
        await(in);
        Throwing.armed = true;
        Object[] thrown = {"nothing"};
        Thread writer = start(() -> {
            try {
                m.put(b, 2);
            } catch (IllegalStateException e) {
                thrown[0] = e.getMessage();
            }
        });
        stopped(writer);
        go.countDown();
        writer.join();
        computing.join();
        Throwing.armed = false;
        System.out.println("put, equals throws beside a compute of its hash: threw " + thrown[0]);
        Thread next = start(() -> m.put(b, 3));
        next.join(LONG_ENOUGH_MS);
        System.out.println("the next put of that key: " + (next.isAlive() ? "STUCK" : "finished, " + m.get(b)));
    }

    /** Equal keys that are different objects, whose {@code equals} takes a lock when told to. */
    static final class Locking {
        static final Object LOCK = new Object();
        static boolean armed;
        final int id;

        Locking(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (armed) {
                synchronized (LOCK) {
                    return o instanceof Locking && ((Locking) o).id == id;
                }
            }
            return o instanceof Locking && ((Locking) o).id == id;
        }

        @Override
        public int hashCode() {
            return 11;
        }
    }

    /** The thread that holds the key does not wait for one that only wants it, and waits for a lock this thread has. */
    static void holderGoesFirst() throws Exception {
        ConcurrentHashMap<Locking, Integer> m = new ConcurrentHashMap<>();
        m.put(new Locking(1), 1);
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread holder = start(() -> m.compute(new Locking(1), (k, v) -> {
            synchronized (Locking.LOCK) {
                in.countDown();
                await(go);
                m.put(new Locking(1), 5);
            }
            return 6;
        }));
        await(in);
        Locking.armed = true;
        Thread wanting = start(() -> m.compute(new Locking(1), (k, v) -> 7));
        stopped(wanting);
        go.countDown();
        holder.join(LONG_ENOUGH_MS);
        wanting.join(LONG_ENOUGH_MS);
        System.out.println("put of an equal key inside compute, another's compute stopped on a lock the first holds: " + (holder.isAlive() || wanting.isAlive() ? "STUCK" : "finished"));
    }

    /** A walk of a table the map has outgrown does not ask the keys for their hash again. */
    static void walkOfOutgrownTable() {
        ConcurrentHashMap<Counted, Integer> m = new ConcurrentHashMap<>();
        for (int i = 0; i < 8; i++) {
            m.put(new Counted(i), i);
        }
        Iterator<Counted> it = m.keySet().iterator();
        for (int i = 8; i < 40; i++) {
            m.put(new Counted(i), i);
        }
        Counted.counts();
        while (it.hasNext()) {
            it.next();
        }
        System.out.println("walk of an outgrown table: " + Counted.counts());
    }

    public static void main(String[] args) throws Exception {
        recursion();
        againstWriter("replace(k, equal, new), a writer storing equal values", (m, v) -> m.replace("k", v, new Slow(1)));
        againstWriter("remove(k, equal), a writer storing equal values", (m, v) -> m.remove("k", v));
        besideACompute();
        equalsThrowsBesideACompute();
        holderGoesFirst();
        walkOfOutgrownTable();
    }
}
