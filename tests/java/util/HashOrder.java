package util;

import java.util.*;

/** The order in which HashMap and HashSet hand out keys: hashing, table sizes and resize points. */
public class HashOrder {
    /** A key whose hash code is chosen by the test; equal ids are equal keys. */
    static final class K implements Comparable<K> {
        final int id;
        final int hash;
        K(int id, int hash) { this.id = id; this.hash = hash; }
        public int hashCode() { return hash; }
        public boolean equals(Object o) { return o instanceof K && ((K) o).id == id; }
        public int compareTo(K o) { return Integer.compare(id, o.id); }
        public String toString() { return "k" + id; }
    }

    static Random rnd = new Random(12345);

    static void show(String label, Map<?, ?> m) {
        System.out.println(label + " " + m.size() + " " + m.keySet());
    }

    static Map<Integer, Integer> filled(Map<Integer, Integer> m, int n, int mul) {
        for (int i = 0; i < n; i++) m.put(i * mul, i);
        return m;
    }

    public static void main(String[] args) {
        for (int n : new int[]{1, 5, 12, 13, 24, 25, 48, 49, 96, 97}) {
            show("seq n=" + n, filled(new HashMap<Integer, Integer>(), n, 1));
        }
        for (int mul : new int[]{3, 7, 16, 17, 100, 1024, 65536, -1, -37}) {
            show("mul " + mul, filled(new HashMap<Integer, Integer>(), 30, mul));
        }
        Map<Integer, Integer> desc = new HashMap<>();
        for (int i = 60; i >= 0; i--) desc.put(i * 5, i);
        show("descending", desc);
        for (int cap : new int[]{0, 1, 2, 3, 5, 9, 16, 17, 31, 33, 100}) {
            show("cap " + cap, filled(new HashMap<Integer, Integer>(cap), 40, 11));
        }
        for (float lf : new float[]{0.1f, 0.5f, 0.75f, 1f, 2f, 10f}) {
            show("lf " + lf, filled(new HashMap<Integer, Integer>(4, lf), 40, 13));
            show("lf16 " + lf, filled(new HashMap<Integer, Integer>(16, lf), 40, 13));
        }
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog", "and", "runs", "far", "away", "from", "here", "today"};
        Map<String, Integer> sm = new HashMap<>();
        for (String w : words) sm.put(w, w.length());
        show("words", sm);
        Set<String> ss = new HashSet<>(Arrays.asList(words));
        System.out.println("wordset " + ss);
        Set<Character> cs = new HashSet<>();
        for (char c : "hello world, this is a set of characters".toCharArray()) cs.add(c);
        System.out.println("chars " + cs);
        Set<Long> ls = new HashSet<>();
        for (long l = 1; l < 1L << 40; l *= 7) ls.add(l);
        System.out.println("longs " + ls);
        Set<Double> ds = new HashSet<>(Arrays.asList(1.5, 2.5, -0.0, 0.0, 100.25, 3.0, Double.NaN));
        System.out.println("doubles " + ds);
        Map<Object, String> mixed = new HashMap<>();
        mixed.put(null, "nul"); mixed.put(1, "i"); mixed.put("1", "s"); mixed.put('1', "c"); mixed.put(1L, "l"); mixed.put(true, "b"); mixed.put(1.0, "d");
        show("mixed", mixed);
        System.out.println(mixed.get(null) + " " + mixed.containsKey(null) + " " + mixed.remove(null) + " " + mixed.containsKey(null));
        Map<Integer, Integer> r = new HashMap<>();
        for (int i = 0; i < 200; i++) r.put(rnd.nextInt(1000), i);
        show("random200", r);
        for (int i = 0; i < 100; i++) r.remove(rnd.nextInt(1000));
        show("after removals", r);
        for (int i = 0; i < 50; i++) r.put(rnd.nextInt(1000), -i);
        show("after reinserts", r);
        r.keySet().removeIf(x -> x % 3 == 0);
        show("after removeIf", r);
        Map<Integer, Integer> c1 = new HashMap<>(r);
        Map<Integer, Integer> c2 = new HashMap<>();
        c2.putAll(r);
        @SuppressWarnings("unchecked") Map<Integer, Integer> c3 = (Map<Integer, Integer>) ((HashMap<Integer, Integer>) r).clone();
        System.out.println(c1.keySet().equals(c2.keySet()) + " " + c1.keySet().toString().equals(c2.keySet().toString()) + " " + c3.keySet().toString().equals(c1.keySet().toString()));
        Map<Integer, Integer> p = filled(new HashMap<Integer, Integer>(), 20, 1);
        p.putAll(filled(new LinkedHashMap<Integer, Integer>(), 5, 1000));
        show("putAll into 20", p);
        Map<Integer, Integer> big = filled(new LinkedHashMap<Integer, Integer>(), 200, 3);
        show("copy of 200", new HashMap<Integer, Integer>(big));
        Map<Integer, Integer> tiny = new HashMap<>(2);
        tiny.putAll(filled(new LinkedHashMap<Integer, Integer>(), 13, 17));
        show("putAll into tiny", tiny);
        Set<Integer> hs = new HashSet<>(filled(new LinkedHashMap<Integer, Integer>(), 13, 19).keySet());
        System.out.println("hashset of 13 " + hs);
        Set<Integer> hs2 = new HashSet<>(40);
        for (int i = 0; i < 30; i++) hs2.add(i * 32);
        System.out.println("hashset cap 40 " + hs2);
        Map<K, Integer> col = new HashMap<>();
        for (int i = 0; i < 8; i++) col.put(new K(i, 5), i);
        show("same hash x8", col);
        col.put(new K(8, 5), 8);
        show("same hash x9 (resizes while small)", col);
        for (int i = 9; i < 30; i++) col.put(new K(i, (i % 3) * 64), i);
        show("three buckets", col);
        for (int i = 0; i < 60; i++) col.put(new K(100 + i, 7), i);
        show("treeified bin", col);
        for (int i = 100; i < 160; i += 2) col.remove(new K(i, 7));
        show("tree bin after removals", col);
        for (int i = 100; i < 150; i += 3) col.put(new K(i, 7), -1);
        show("tree bin after reinserts", col);
        for (int i = 0; i < 40; i++) col.put(new K(1000 + i, 1 << 16), i);
        show("high-bit hash", col);
        Iterator<K> kit = col.keySet().iterator();
        int removed = 0;
        while (kit.hasNext()) { K k = kit.next(); if (k.id % 4 == 0) { kit.remove(); removed++; } }
        show("iterator removed " + removed, col);
        Map<K, Integer> comp = new HashMap<>();
        for (int i = 0; i < 6; i++) comp.put(new K(i, 3), i);
        comp.computeIfAbsent(new K(10, 3), k -> 1);
        comp.compute(new K(11, 3), (k, v) -> 2);
        comp.merge(new K(12, 3), 3, (a, b) -> a);
        comp.put(new K(13, 3), 4);
        show("compute family goes first", comp);
        Map<Integer, String> nm = new HashMap<>();
        for (int i = 0; i < 20; i++) nm.put(i % 2 == 0 ? i : -i, "v" + i);
        nm.put(null, "null");
        show("negatives and null", nm);
        System.out.println(nm);
        Map<Integer, Integer> grow = new HashMap<>();
        for (int i = 0; i < 1000; i++) grow.put(i * 31, i);
        StringBuilder sb = new StringBuilder();
        int k = 0;
        for (int key : grow.keySet()) { if (k++ % 50 == 0) sb.append(key).append(' '); }
        System.out.println(grow.size() + " " + sb);
        System.out.println("done");
    }
}
