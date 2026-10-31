package concurrent;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Rules of ConcurrentHashMap and the copy-on-write list that a reviewer's probes brought up: the order of a small
 * map, the views and null, what clear waits for, and that no search has to start over for what cannot change its
 * answer. Where threads are needed, one is stopped inside user code and released by the test, so no line rests on time.
 */
public class CollectionPins {
    interface Op {
        Object run() throws Exception;
    }

    static void show(String label, Op op) {
        String out;
        try {
            out = String.valueOf(op.run());
        } catch (Throwable t) {
            out = t.getClass().getName();
        }
        System.out.println(label + " = " + out);
    }

    static Thread start(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Waits until the thread is stopped or finished: the two answers a thread can give to "does it wait?". */
    static String waits(Thread t) throws InterruptedException {
        for (;;) {
            Thread.State s = t.getState();
            if (s == Thread.State.TERMINATED) {
                return "ran";
            }
            if (s == Thread.State.BLOCKED || s == Thread.State.WAITING) {
                return "waits";
            }
            Thread.sleep(1);
        }
    }

    // The order of a map that has not grown: keys in bins of their own, whose hash codes reach above bit 16.
    static void smallOrder() {
        ConcurrentHashMap<String, Integer> words = new ConcurrentHashMap<>();
        for (String w : new String[] {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india"}) {
            words.put(w, w.length());
        }
        show("small map of strings, in order", () -> words);
        ConcurrentHashMap<Integer, Integer> ints = new ConcurrentHashMap<>();
        for (int k = 1; k <= 8; k++) {
            ints.put(k << 16 | (2 * k & 15), k);
        }
        show("small map of ints, in order", () -> ints);
        show("small keys in order", () -> ints.keySet());
    }

    static void viewsAndNull() {
        ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
        m.put("x", 1);
        Set<Object> withNull = new LinkedHashSet<>(Arrays.asList("x", null));
        Set<Object> nullFirst = new LinkedHashSet<>(Arrays.asList(null, "x"));
        show("key view equals a set holding null", () -> m.keySet().equals(withNull));
        show("key view equals a set with null first", () -> m.keySet().equals(nullFirst));
        show("key view containsAll with null", () -> m.keySet().containsAll(withNull));
        show("key view containsAll, a null only", () -> m.keySet().containsAll(Arrays.asList((Object) null)));
        show("value view containsAll with null", () -> m.values().containsAll(Arrays.asList(1, null)));
        show("entry view equals a set holding null", () -> m.entrySet().equals(withNull));
        show("key view contains null still throws", () -> m.keySet().contains(null));
        show("value view contains null still throws", () -> m.values().contains(null));
        show("key view containsAll of null throws", () -> m.keySet().containsAll(null));
    }

    /** A key of its own hash, for the bins of a compute that stays running. */
    static final class Key {
        final int id;

        Key(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && ((Key) o).id == id;
        }

        @Override
        public String toString() {
            return "K" + id;
        }
    }

    static void clearWaits(String label, boolean present, Consumer<ConcurrentHashMap<Key, Integer>> clear) throws Exception {
        ConcurrentHashMap<Key, Integer> m = new ConcurrentHashMap<>();
        Key k = new Key(5);
        if (present) {
            m.put(k, 7);
        }
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Thread computing = start(() -> m.compute(k, (x, v) -> {
            in.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return 100;
        }));
        in.await();
        Thread clearing = start(() -> clear.accept(m));
        show(label + " during a compute", () -> waits(clearing));
        // A bin above the compute's: the oracle's clear has not reached it, and here it does not stop a writer.
        Thread other = start(() -> m.put(new Key(9), 1));
        show(label + ", a put to a later bin meanwhile", () -> waits(other));
        go.countDown();
        computing.join();
        clearing.join();
        other.join();
        show(label + ", at the end", () -> m);
    }

    static void clearInside() {
        for (int n : new int[] {0, 1, 3}) {
            String of = " of " + n;
            Function<Integer, ConcurrentHashMap<String, Integer>> fill = size -> {
                ConcurrentHashMap<String, Integer> m = new ConcurrentHashMap<>();
                for (int i = 0; i < size; i++) {
                    m.put("k" + i, i);
                }
                return m;
            };
            ConcurrentHashMap<String, Integer> a = fill.apply(n);
            show("clear inside compute" + of, () -> a.compute("k0", (k, v) -> {
                a.clear();
                return 100;
            }) + " " + new TreeMap<>(a) + " " + a.size());
            ConcurrentHashMap<String, Integer> b = fill.apply(n);
            show("clear inside computeIfAbsent" + of, () -> b.computeIfAbsent("k0", k -> {
                b.clear();
                return 100;
            }) + " " + new TreeMap<>(b) + " " + b.size());
            ConcurrentHashMap<String, Integer> c = fill.apply(n);
            show("clear inside computeIfPresent" + of, () -> c.computeIfPresent("k0", (k, v) -> {
                c.clear();
                return 100;
            }) + " " + new TreeMap<>(c) + " " + c.size());
            ConcurrentHashMap<String, Integer> d = fill.apply(n);
            show("clear inside merge" + of, () -> d.merge("k0", 5, (x, y) -> {
                d.clear();
                return 100;
            }) + " " + new TreeMap<>(d) + " " + d.size());
            ConcurrentHashMap<String, Integer> e = fill.apply(n);
            show("clear inside compute of a new key" + of, () -> e.compute("new", (k, v) -> {
                e.keySet().clear();
                return 100;
            }) + " " + new TreeMap<>(e) + " " + e.size());
        }
    }

    /** Each equals it is asked, up to a limit, runs a hook first, so a search that started over is counted. */
    static final class Asks {
        final int id;
        final int hash;
        final Runnable hook;
        int calls;

        Asks(int id, int hash, Runnable hook) {
            this.id = id;
            this.hash = hash;
            this.hook = hook;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            calls++;
            if (calls <= 12) {
                hook.run();
            }
            return o instanceof Asks && ((Asks) o).id == id;
        }
    }

    /** Starts a compute of a new key in another bin, and leaves it running until the end of the test. */
    static Runnable computeElsewhere(ConcurrentHashMap<Object, Integer> m, CountDownLatch go, int[] next) {
        return () -> {
            CountDownLatch in = new CountDownLatch(1);
            Key k = new Key(1000 + next[0]++);
            start(() -> m.compute(k, (x, v) -> {
                in.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return 1;
            }));
            try {
                in.await();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** These fail on a map whose writer starts over when any computation starts: the hook then runs to its limit. */
    static void writerAndOtherComputes(boolean oneRunning) throws Exception {
        ConcurrentHashMap<Object, Integer> m = new ConcurrentHashMap<>();
        Asks there = new Asks(1, 7, () -> {
        });
        m.put(there, 1);
        CountDownLatch go = new CountDownLatch(1);
        int[] next = {0};
        Runnable hook = computeElsewhere(m, go, next);
        if (oneRunning) {
            hook.run();
        }
        Asks key = new Asks(2, 7, hook);
        Thread writer = start(() -> m.put(key, 2));
        writer.join();
        String how = oneRunning ? "a compute of another bin already running" : "no compute running";
        show("put, a new compute of another bin at each equals, " + how + ": equals calls at most 2", () -> key.calls <= 2);
        show("that put is in", () -> m.get(key));
        go.countDown();
    }

    /** Does the given write at the "first" element's turn; at "late", a second look, sees whether a writer gets through. */
    static void secondLook(String label, Object[] start, Consumer<CopyOnWriteArrayList<Object>> write, Function<CopyOnWriteArrayList<Object>, Object> op) throws Exception {
        CopyOnWriteArrayList<Object> l = new CopyOnWriteArrayList<>(start);
        String[] writer = {"no second look"};
        Thread[] writing = new Thread[1];
        boolean[] wrote = {false};
        Second.asked.clear();
        Second.hook = element -> {
            if ("first".equals(element) && !wrote[0]) {
                wrote[0] = true;
                write.accept(l);
            } else if ("late".equals(element)) {
                writing[0] = start(() -> l.add("w"));
                try {
                    writer[0] = "a writer " + waits(writing[0]);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        show(label, () -> op.apply(l) + ", " + writer[0] + ", asked " + Second.asked);
        if (writing[0] != null) {
            writing[0].join();
        }
        show(label + ", list", () -> l);
    }

    /** The argument of the search; {@code equals} tells the hook what it is being compared with. */
    static final class Second {
        static Consumer<Object> hook;
        static final List<String> asked = new ArrayList<>();
        final String name;

        Second(String name) {
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            asked.add(String.valueOf(o));
            hook.accept(o);
            return o instanceof Second && ((Second) o).name.equals(name);
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static void listSecondLook() throws Exception {
        Second k = new Second("k");
        Object[] found = {"first", "second", new Second("k"), "tail"};
        Object[] absent = {"first", "second"};
        Function<CopyOnWriteArrayList<Object>, Object> remove = l -> l.remove((Object) k);
        Function<CopyOnWriteArrayList<Object>, Object> addIf = l -> l.addIfAbsent(k);
        secondLook("remove, a new element before the match", found, l -> l.add(0, "late"), remove);
        secondLook("remove, a set before the match", found, l -> l.set(1, "late"), remove);
        secondLook("remove, an add behind the match", found, l -> l.add("late"), remove);
        secondLook("remove, the match gone", found, l -> {
            l.remove(2);
            l.add("late");
        }, remove);
        secondLook("addIfAbsent, an add", absent, l -> l.add("late"), addIf);
        secondLook("addIfAbsent, a set", absent, l -> l.set(1, "late"), addIf);
        secondLook("addIfAbsent, shorter", absent, l -> l.remove(1), addIf);
        secondLook("addIfAbsent, the key added", absent, l -> l.add(new Second("k")), addIf);
    }

    /** A write comes in at each equals asked, up to a limit: a search that must see one array twice would run to it. */
    static void listWritesAtEachEquals(String label, Object[] start, Function<CopyOnWriteArrayList<Object>, Object> op) throws Exception {
        CopyOnWriteArrayList<Object> l = new CopyOnWriteArrayList<>(start);
        Second.asked.clear();
        List<Thread> writers = new ArrayList<>();
        Second.hook = element -> {
            if (Second.asked.size() <= 30) {
                Thread w = start(() -> l.set(0, "x" + Second.asked.size()));
                writers.add(w);
                try {
                    waits(w);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        show(label, () -> op.apply(l) + " after " + Second.asked.size() + " calls of equals");
        for (Thread w : writers) {
            w.join();
        }
    }

    /** No walk may fail on a bin that is emptied under it: the slot holds all there is to read. */
    static void walkersOnEmptiedBin() throws Exception {
        ConcurrentHashMap<Ranked, Integer> m = new ConcurrentHashMap<>();
        boolean[] done = {false};
        int[] failures = {0};
        Thread[] walkers = new Thread[4];
        for (int i = 0; i < walkers.length; i++) {
            walkers[i] = start(() -> {
                while (!done[0]) {
                    try {
                        for (Ranked k : m.keySet()) {
                            if (k == null) {
                                failures[0]++;
                            }
                        }
                    } catch (RuntimeException e) {
                        failures[0]++;
                    }
                }
            });
        }
        Ranked[] keys = new Ranked[17];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = new Ranked(i);
        }
        for (int cycle = 0; cycle < 3000; cycle++) {
            for (Ranked k : keys) {
                m.put(k, 1);
            }
            for (Ranked k : keys) {
                m.remove(k);
            }
        }
        done[0] = true;
        for (Thread w : walkers) {
            w.join();
        }
        show("long bin emptied under walkers, failures", () -> failures[0]);
    }

    static void staleEmptyWindow() {
        CopyOnWriteArrayList<Integer> m = new CopyOnWriteArrayList<>(Arrays.asList(1, 2, 3));
        List<Integer> empty = m.subList(1, 1);
        List<Integer> full = m.subList(0, 2);
        m.add(9);
        show("stale empty sub-list removeIf", () -> empty.removeIf(x -> true));
        show("stale empty sub-list removeAll", () -> empty.removeAll(Arrays.asList(1)));
        show("stale empty sub-list retainAll", () -> empty.retainAll(Arrays.asList(1)));
        show("stale sub-list removeIf", () -> full.removeIf(x -> true));
        show("stale empty sub-list size", () -> empty.size());
    }

    static final class Ranked implements Comparable<Ranked> {
        final int id;

        Ranked(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return 7;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Ranked && ((Ranked) o).id == id;
        }

        @Override
        public int compareTo(Ranked o) {
            return Integer.compare(id, o.id);
        }
    }

    static void removedIsCollected() throws Exception {
        ConcurrentHashMap<Ranked, Object> m = new ConcurrentHashMap<>();
        // Enough for a bin of its own to turn into a tree (the rule is about a tree made from a chain), too few for the
        // table to grow again after that, which would drop every old link anyway.
        for (int i = 0; i < 20; i++) {
            m.put(new Ranked(i), i == 3 ? new byte[16] : "v");
        }
        WeakReference<Object> gone = new WeakReference<>(m.get(new Ranked(3)));
        m.remove(new Ranked(3));
        for (int i = 0; i < 200 && gone.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        show("value removed from a bin that became a tree is collected", () -> gone.get() == null);
    }

    /** A far negative timeout is a past time like any other: every queue answers at once. */
    static void farNegative() throws Exception {
        for (long nanos : new long[] {Long.MIN_VALUE, Long.MIN_VALUE + 1, -Long.MAX_VALUE / 2}) {
            String at = " at " + nanos + ": ";
            timed("LinkedBlockingQueue poll" + at, () -> new LinkedBlockingQueue<Integer>().poll(nanos, TimeUnit.NANOSECONDS));
            timed("LinkedBlockingQueue offer" + at, () -> full().offer(2, nanos, TimeUnit.NANOSECONDS));
            timed("LinkedBlockingDeque pollFirst" + at, () -> new LinkedBlockingDeque<Integer>().pollFirst(nanos, TimeUnit.NANOSECONDS));
            timed("LinkedBlockingDeque pollLast" + at, () -> new LinkedBlockingDeque<Integer>().pollLast(nanos, TimeUnit.NANOSECONDS));
            timed("LinkedTransferQueue poll" + at, () -> new LinkedTransferQueue<Integer>().poll(nanos, TimeUnit.NANOSECONDS));
            timed("LinkedTransferQueue tryTransfer" + at, () -> new LinkedTransferQueue<Integer>().tryTransfer(1, nanos, TimeUnit.NANOSECONDS));
        }
        timed("LinkedBlockingQueue poll, most negative days", () -> new LinkedBlockingQueue<Integer>().poll(Long.MIN_VALUE, TimeUnit.DAYS));
        timed("LinkedTransferQueue tryTransfer, most negative days", () -> new LinkedTransferQueue<Integer>().tryTransfer(1, Long.MIN_VALUE, TimeUnit.DAYS));
    }

    static LinkedBlockingQueue<Integer> full() {
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>(1);
        q.add(1);
        return q;
    }

    /** With a watchdog, so that the failure this guards against is a line and not a stuck run. */
    static void timed(String label, Op op) throws Exception {
        Object[] answer = {"no answer"};
        Thread t = start(() -> {
            try {
                answer[0] = op.run();
            } catch (Exception e) {
                answer[0] = e;
            }
        });
        t.join(20000);
        System.out.println(label + " = " + answer[0]);
    }

    public static void main(String[] args) throws Exception {
        smallOrder();
        viewsAndNull();
        clearWaits("clear of a present key", true, ConcurrentHashMap::clear);
        clearWaits("clear, key absent", false, ConcurrentHashMap::clear);
        clearWaits("key view clear", true, m -> m.keySet().clear());
        clearInside();
        writerAndOtherComputes(false);
        writerAndOtherComputes(true);
        listSecondLook();
        Second k = new Second("k");
        listWritesAtEachEquals("remove, a write at each equals", new Object[] {"a", "b", "c", new Second("k")}, l -> l.remove((Object) k));
        listWritesAtEachEquals("addIfAbsent, a write at each equals", new Object[] {"a", "b", "c"}, l -> l.addIfAbsent(k));
        staleEmptyWindow();
        removedIsCollected();
        walkersOnEmptiedBin();
        farNegative();
    }
}
