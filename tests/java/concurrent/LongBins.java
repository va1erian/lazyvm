package concurrent;

import java.util.*;
import java.util.concurrent.*;

/**
 * ConcurrentHashMap with many keys of one hash. The first part fails on time alone, by a wide margin: a
 * map that searched such a bin in a line would take minutes over it and hit the runner's limit.
 */
public class LongBins {
    static final int MANY = 20000;

    interface Op { Object run() throws Exception; }

    static void show(String label, Op op) {
        try {
            System.out.println(label + " = " + op.run());
        } catch (Exception e) {
            System.out.println(label + " ! " + e);
        }
    }

    static final class Ranked implements Comparable<Ranked> {
        final int v;
        Ranked(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof Ranked && ((Ranked) o).v == v; }
        @Override public int compareTo(Ranked o) { return Integer.compare(v, o.v); }
    }

    /** compareTo says 0 for keys that differ. */
    static final class Tied implements Comparable<Tied> {
        final int v;
        Tied(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof Tied && ((Tied) o).v == v; }
        @Override public int compareTo(Tied o) { return 0; }
    }

    static final class Plain {
        final int v;
        Plain(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof Plain && ((Plain) o).v == v; }
    }

    /** Declares an order on another class: comparing two of these would be a ClassCastException. */
    static final class OrderedByOther implements Comparable<Object> {
        final int v;
        OrderedByOther(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof OrderedByOther && ((OrderedByOther) o).v == v; }
        @Override public int compareTo(Object o) { return ((String) o).length(); }
    }

    static final class Throwing implements Comparable<Throwing> {
        static boolean armed;
        final int v;
        Throwing(int v) { this.v = v; }
        @Override public int hashCode() { return 42; }
        @Override public boolean equals(Object o) { return o instanceof Throwing && ((Throwing) o).v == v; }
        @Override public int compareTo(Throwing o) {
            if (armed) throw new IllegalStateException("compare");
            return Integer.compare(v, o.v);
        }
    }

    /** Would take minutes if colliding keys were searched in a line. */
    static void many() {
        ConcurrentHashMap<Ranked, Integer> m = new ConcurrentHashMap<>();
        for (int i = 0; i < MANY; i++) m.put(new Ranked(i), i);
        show("many: size", m::size);
        long sum = 0;
        for (int i = 0; i < MANY; i++) sum += m.get(new Ranked(i));
        System.out.println("many: sum of gets = " + sum);
        show("many: miss", () -> m.get(new Ranked(MANY)));
        for (int i = 0; i < MANY; i += 2) m.remove(new Ranked(i));
        show("many: after removing half", m::size);
        int present = 0;
        for (int i = 0; i < MANY; i++) if (m.containsKey(new Ranked(i))) present++;
        System.out.println("many: still found = " + present);
    }

    /** All of the map's operations on a bin that is a tree, against a plain HashMap told the same. */
    static <K> void sameAsHashMap(String name, java.util.function.IntFunction<K> key, int count) {
        ConcurrentHashMap<K, Integer> m = new ConcurrentHashMap<>();
        HashMap<K, Integer> model = new HashMap<>();
        Random r = new Random(5);
        int differences = 0;
        for (int i = 0; i < count * 6; i++) {
            K k = key.apply(r.nextInt(count));
            int x = r.nextInt(1000);
            Object mine;
            Object theirs;
            switch (r.nextInt(9)) {
                case 0: case 1: case 2: mine = m.put(k, x); theirs = model.put(k, x); break;
                case 3: mine = m.remove(k); theirs = model.remove(k); break;
                case 4: mine = m.putIfAbsent(k, x); theirs = model.putIfAbsent(k, x); break;
                case 5: mine = m.get(k); theirs = model.get(k); break;
                case 6: mine = m.merge(k, x, Integer::sum); theirs = model.merge(k, x, Integer::sum); break;
                case 7: mine = m.replace(k, x); theirs = model.replace(k, x); break;
                default: mine = m.compute(k, (a, b) -> b == null ? null : (Integer) x); theirs = model.compute(k, (a, b) -> b == null ? null : (Integer) x); break;
            }
            if (!Objects.equals(mine, theirs)) differences++;
        }
        final int found = differences;
        show(name + ": answers that differ from a HashMap", () -> found);
        show(name + ": same mappings", () -> m.equals(model) && model.equals(m) && m.size() == model.size());
        show(name + ": iteration has each once", () -> {
            Set<K> seen = new HashSet<>();
            int n = 0;
            for (K k : m.keySet()) {
                seen.add(k);
                n++;
            }
            return n == m.size() && seen.equals(model.keySet());
        });
    }

    static void kinds() {
        sameAsHashMap("ordered", Ranked::new, 300);
        sameAsHashMap("ties", Tied::new, 200);
        sameAsHashMap("no order", Plain::new, 200);
        sameAsHashMap("order on another class", OrderedByOther::new, 200);
        sameAsHashMap("mixed", i -> i % 3 == 0 ? new Ranked(i) : i % 3 == 1 ? new Tied(i) : (Object) new Plain(i), 300);
        sameAsHashMap("strings of one hash", i -> {
            StringBuilder s = new StringBuilder();
            for (int bit = 0; bit < 9; bit++) s.append((i >> bit & 1) == 0 ? "Aa" : "BB");
            return s.toString();
        }, 300);
    }

    /** A growing table splits long bins by hash; a key of a few distinct hashes stays findable. */
    static void growth() {
        ConcurrentHashMap<Object, Integer> m = new ConcurrentHashMap<>();
        final class Few implements Comparable<Few> {
            final int v;
            Few(int v) { this.v = v; }
            @Override public int hashCode() { return (v % 8) << 8; }
            @Override public boolean equals(Object o) { return o instanceof Few && ((Few) o).v == v; }
            @Override public int compareTo(Few o) { return Integer.compare(v, o.v); }
        }
        for (int i = 0; i < 3000; i++) m.put(new Few(i), i);
        int misses = 0;
        for (int i = 0; i < 3000; i++) if (m.getOrDefault(new Few(i), -1) != i) misses++;
        final int found = misses;
        show("growth: misses", () -> found);
        show("growth: size", m::size);
        for (int i = 0; i < 3000; i++) m.remove(new Few(i));
        show("growth: emptied", m::isEmpty);
        show("growth: nothing left to iterate", () -> m.keySet().iterator().hasNext());
        m.put(new Few(1), 1);
        show("growth: usable again", () -> m.get(new Few(1)));
    }

    static void iterators() {
        ConcurrentHashMap<Ranked, Integer> m = new ConcurrentHashMap<>();
        for (int i = 0; i < 40; i++) m.put(new Ranked(i), i);
        Iterator<Map.Entry<Ranked, Integer>> it = m.entrySet().iterator();
        Map.Entry<Ranked, Integer> first = it.next();
        show("entry writes through in a long bin", () -> { first.setValue(777); return m.get(first.getKey()); });
        it.remove();
        show("iterator remove in a long bin", () -> m.containsKey(first.getKey()) + " " + m.size());
        show("remove twice", () -> { it.remove(); return "no"; });
        int left = 0;
        while (it.hasNext()) {
            it.next();
            left++;
        }
        final int walked = left;
        show("the rest of the walk", () -> walked);
        Iterator<Integer> values = m.values().iterator();
        values.next();
        m.clear();
        show("after clear", () -> m.size() + " " + m.get(new Ranked(1)));
        m.put(new Ranked(3), 3);
        show("written after clear", () -> m);
    }

    /** What a compareTo throws reaches the caller and changes nothing, once the bin has an order to keep. */
    static void throwing() {
        ConcurrentHashMap<Throwing, Integer> m = new ConcurrentHashMap<>();
        for (int i = 0; i < 40; i++) m.put(new Throwing(i), i);
        Throwing.armed = true;
        show("put of a new key, compareTo throws", () -> m.put(new Throwing(100), 1));
        show("get of an absent key, compareTo throws", () -> m.get(new Throwing(101)));
        show("remove of an absent key, compareTo throws", () -> m.remove(new Throwing(102)));
        Throwing.armed = false;
        show("after the throws", () -> m.size() + " " + m.containsKey(new Throwing(100)));
    }

    public static void main(String[] args) {
        many();
        kinds();
        growth();
        iterators();
        throwing();
    }
}
