package util;

import java.util.*;
import java.util.function.*;

/** LinkedHashMap and LinkedHashSet: insertion order, access order, removeEldestEntry caches. */
public class LinkedHashMaps {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** A least-recently-used cache holding at most {@code cap} entries. */
    static class Lru<K, V> extends LinkedHashMap<K, V> {
        final int cap;
        final List<K> evicted = new ArrayList<>();

        Lru(int cap) {
            super(16, 0.75f, true);
            this.cap = cap;
        }

        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            boolean drop = size() > cap;
            if (drop) evicted.add(eldest.getKey());
            return drop;
        }
    }

    public static void main(String[] args) {
        Map<String, Integer> ins = new LinkedHashMap<>();
        String[] keys = {"pear", "apple", "fig", "kiwi", "apple", "plum", "fig"};
        for (int i = 0; i < keys.length; i++) ins.put(keys[i], i);
        System.out.println(ins + " " + ins.keySet() + " " + ins.values() + " " + ins.entrySet());
        ins.remove("kiwi"); ins.put("kiwi", 99);
        System.out.println(ins);
        ins.get("pear"); ins.getOrDefault("fig", 0); ins.containsKey("plum");
        System.out.println(ins);
        Map<String, Integer> acc = new LinkedHashMap<>(16, 0.75f, true);
        for (String k : new String[]{"a", "b", "c", "d"}) acc.put(k, k.charAt(0) - 'a');
        System.out.println(acc);
        acc.get("a"); System.out.println(acc);
        acc.get("c"); System.out.println(acc);
        acc.get("zzz"); System.out.println(acc);
        acc.put("b", 100); System.out.println(acc);
        acc.putIfAbsent("d", 5); System.out.println("putIfAbsent " + acc);
        acc.getOrDefault("a", 0); System.out.println("getOrDefault " + acc);
        acc.containsKey("c"); acc.containsValue(3); System.out.println("contains " + acc);
        acc.replace("c", 7); System.out.println("replace " + acc);
        acc.replace("a", 0, 9); System.out.println("replace3 " + acc);
        acc.replace("a", 1000, 1); System.out.println("replace3 miss " + acc);
        acc.compute("d", (k, v) -> v + 1); System.out.println("compute " + acc);
        acc.computeIfPresent("b", (k, v) -> v + 1); System.out.println("computeIfPresent " + acc);
        acc.computeIfAbsent("c", k -> 0); System.out.println("computeIfAbsent present " + acc);
        acc.computeIfAbsent("e", k -> 5); System.out.println("computeIfAbsent new " + acc);
        acc.merge("a", 1, Integer::sum); System.out.println("merge " + acc);
        acc.remove("zzz", 1); acc.remove("e", 5); System.out.println("remove " + acc);
        t("forEach access", () -> { StringBuilder sb = new StringBuilder(); acc.forEach((k, v) -> sb.append(k).append(v)); return sb + " " + acc; });
        t("iterate then get", () -> { for (String k : new ArrayList<>(acc.keySet())) acc.get(k); return acc; });
        t("get during iteration", () -> { for (String k : acc.keySet()) acc.get(k); return acc; });
        t("put existing during iteration", () -> { for (String k : acc.keySet()) acc.put(k, 0); return acc; });
        t("entrySet setValue", () -> { for (Map.Entry<String, Integer> e : acc.entrySet()) e.setValue(e.getValue() + 1); return acc; });
        Lru<Integer, String> lru = new Lru<>(3);
        for (int i = 1; i <= 3; i++) lru.put(i, "v" + i);
        lru.get(1); lru.put(4, "v4");
        System.out.println(lru + " evicted " + lru.evicted);
        lru.get(3); lru.put(5, "v5"); lru.put(3, "again"); lru.put(6, "v6");
        System.out.println(lru + " evicted " + lru.evicted);
        lru.putAll(Collections.singletonMap(7, "v7"));
        lru.computeIfAbsent(8, k -> "v8");
        lru.merge(9, "m", (a, b) -> a + b);
        System.out.println(lru + " evicted " + lru.evicted);
        Lru<Integer, String> lru0 = new Lru<>(0);
        lru0.put(1, "a"); lru0.put(2, "b");
        System.out.println(lru0 + " " + lru0.evicted);
        List<Integer> asked = new ArrayList<>();
        LinkedHashMap<Integer, Integer> every = new LinkedHashMap<Integer, Integer>() {
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { asked.add(e.getKey() * 100 + size()); return false; }
        };
        every.put(1, 1); every.put(1, 2); every.putIfAbsent(2, 2); every.putIfAbsent(2, 3); every.compute(3, (k, v) -> 3); every.compute(3, (k, v) -> 4);
        every.computeIfAbsent(4, k -> 4); every.merge(5, 5, Integer::sum); every.merge(5, 5, Integer::sum); every.compute(9, (k, v) -> null);
        every.putAll(Collections.singletonMap(6, 6)); every.replace(1, 8);
        System.out.println("removeEldestEntry asked (eldest*100+size): " + asked);
        LinkedHashMap<Integer, Integer> cap = new LinkedHashMap<Integer, Integer>() {
            protected boolean removeEldestEntry(Map.Entry<Integer, Integer> e) { return size() > 4; }
        };
        for (int i = 0; i < 10; i++) cap.put(i * 3 % 7, i);
        System.out.println(cap);
        Map<Integer, Integer> big = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) big.put((i * 37) % 101, i);
        StringBuilder sb = new StringBuilder();
        for (int k : big.keySet()) sb.append(k).append(' ');
        System.out.println(sb);
        for (int i = 0; i < 100; i += 3) big.remove((i * 37) % 101);
        System.out.println(big.size() + " " + big.keySet());
        Map<Integer, Integer> copy = new LinkedHashMap<>(big);
        System.out.println(copy.equals(big) + " " + new ArrayList<>(copy.keySet()).equals(new ArrayList<>(big.keySet())));
        Iterator<Map.Entry<Integer, Integer>> it = big.entrySet().iterator();
        while (it.hasNext()) if (it.next().getKey() % 2 == 0) it.remove();
        System.out.println(big);
        t("CME", () -> { for (Integer k : big.keySet()) big.put(k + 1000, 0); return 0; });
        t("accessOrder CME", () -> { LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>(4, 0.75f, true); m.put(1, 1); m.put(2, 2); m.put(3, 3); for (Integer k : m.keySet()) m.get(k); return m; });
        t("forEach CME", () -> { LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>(); m.put(1, 1); m.put(2, 2); StringBuilder seen = new StringBuilder(); try { m.forEach((k, v) -> { seen.append(k).append(' '); if (m.size() < 6) m.put(k + 10, 0); }); } catch (ConcurrentModificationException e) { seen.append("CME"); } return seen + " " + m; });
        t("replaceAll", () -> { LinkedHashMap<Integer, Integer> m = new LinkedHashMap<>(); m.put(3, 1); m.put(1, 2); m.replaceAll((k, v) -> k * 10 + v); return m; });
        t("containsValue", () -> acc.containsValue(100) + " " + acc.containsValue(null));
        t("clear", () -> { Map<String, Integer> m = new LinkedHashMap<>(acc); m.clear(); m.put("x", 1); return m; });
        t("null key and value", () -> { Map<String, String> m = new LinkedHashMap<>(); m.put(null, null); m.put("a", null); m.put("b", "c"); return m + " " + m.get(null) + " " + m.containsKey(null) + " " + m.containsValue(null); });
        t("ctor bad", () -> new LinkedHashMap<String, String>(-1));
        t("ctor bad lf", () -> new LinkedHashMap<String, String>(1, 0f));
        t("ctor bad lf nan", () -> new LinkedHashMap<String, String>(1, Float.NaN, true));
        Set<String> lhs = new LinkedHashSet<>(Arrays.asList("z", "y", "x", "z", "w"));
        lhs.add("a"); lhs.remove("y"); lhs.add("y"); lhs.add("w");
        System.out.println(lhs + " " + lhs.contains("x") + " " + lhs.size());
        lhs.retainAll(Arrays.asList("a", "y", "z", "q"));
        System.out.println(lhs + " " + new LinkedHashSet<Integer>(5).size() + " " + new LinkedHashSet<Integer>(Arrays.asList(3, 1, 2)));
        System.out.println("done");
    }
}
