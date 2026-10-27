package util;

import java.util.*;
import java.util.function.*;

/** The Map default methods on every kind of map, including null values and exception behaviour. */
public class MapDefaults {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    /** A map that only implements the abstract minimum, so every default method runs as written in Map. */
    static class Simple<K, V> extends AbstractMap<K, V> {
        final List<Map.Entry<K, V>> entries = new ArrayList<>();

        public Set<Map.Entry<K, V>> entrySet() {
            return new AbstractSet<Map.Entry<K, V>>() {
                public Iterator<Map.Entry<K, V>> iterator() { return entries.iterator(); }
                public int size() { return entries.size(); }
            };
        }

        public V put(K k, V v) {
            for (Map.Entry<K, V> e : entries) if (Objects.equals(e.getKey(), k)) return e.setValue(v);
            entries.add(new AbstractMap.SimpleEntry<K, V>(k, v));
            return null;
        }
    }

    static void run(String name, Map<String, Integer> m, boolean nullValues) {
        System.out.println("== " + name);
        m.put("a", 1); m.put("b", 2); m.put("c", 3);
        System.out.println(m.getOrDefault("a", 9) + " " + m.getOrDefault("z", 9) + " " + m.getOrDefault("z", null));
        System.out.println(m.putIfAbsent("a", 10) + " " + m.putIfAbsent("d", 4) + " " + m);
        System.out.println(m.remove("a", 2) + " " + m.remove("a", 1) + " " + m.remove("zz", 1) + " " + m);
        System.out.println(m.replace("b", 2, 20) + " " + m.replace("b", 2, 30) + " " + m.replace("zz", 1, 2) + " " + m);
        System.out.println(m.replace("b", 21) + " " + m.replace("zz", 5) + " " + m);
        System.out.println(m.computeIfAbsent("b", k -> 99) + " " + m.computeIfAbsent("e", k -> 5) + " " + m.computeIfAbsent("f", k -> null) + " " + m.containsKey("f") + " " + m);
        System.out.println(m.computeIfPresent("e", (k, v) -> v + 1) + " " + m.computeIfPresent("zz", (k, v) -> 1) + " " + m.computeIfPresent("e", (k, v) -> null) + " " + m.containsKey("e") + " " + m);
        System.out.println(m.compute("b", (k, v) -> v + 1) + " " + m.compute("g", (k, v) -> v == null ? 7 : 8) + " " + m.compute("g", (k, v) -> null) + " " + m.compute("zz", (k, v) -> null) + " " + m.containsKey("g") + " " + m);
        System.out.println(m.merge("b", 100, Integer::sum) + " " + m.merge("h", 100, Integer::sum) + " " + m.merge("h", 1, (x, y) -> null) + " " + m.containsKey("h") + " " + m);
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append(v).append(' '));
        m.replaceAll((k, v) -> v * 2);
        System.out.println(sb + "| " + m);
        t(name + " forEach null", () -> { m.forEach(null); return 0; });
        t(name + " replaceAll null", () -> { m.replaceAll(null); return 0; });
        t(name + " computeIfAbsent null fn", () -> m.computeIfAbsent("x", null));
        t(name + " computeIfPresent null fn", () -> m.computeIfPresent("b", null));
        t(name + " compute null fn", () -> m.compute("b", null));
        t(name + " merge null fn", () -> m.merge("b", 1, null));
        t(name + " merge null value", () -> m.merge("b", null, Integer::sum));
        t(name + " merge null value absent", () -> m.merge("nothere", null, Integer::sum));
        t(name + " putIfAbsent null value", () -> m.putIfAbsent("n1", null) + " " + m.containsKey("n1") + " " + m.get("n1"));
        t(name + " computeIfAbsent over null value", () -> { m.put("n2", null); return m.computeIfAbsent("n2", k -> 5) + " " + m.get("n2"); });
        t(name + " computeIfPresent null value", () -> { m.put("n3", null); return m.computeIfPresent("n3", (k, v) -> 1) + " " + m.containsKey("n3"); });
        t(name + " compute null value", () -> { m.put("n4", null); return m.compute("n4", (k, v) -> v == null ? 42 : v) + " " + m.get("n4"); });
        t(name + " merge over null value", () -> { m.put("n5", null); return m.merge("n5", 9, (x, y) -> x + y) + " " + m.get("n5"); });
        t(name + " getOrDefault null value", () -> { m.put("n6", null); return m.getOrDefault("n6", 5) + " " + m.getOrDefault("n7", 5); });
        t(name + " replace null value", () -> { m.put("n8", null); return m.replace("n8", null, 3) + " " + m.get("n8") + " " + m.replace("n9", null) + " " + m.containsKey("n9"); });
        t(name + " remove null value", () -> { m.put("n10", null); return m.remove("n10", null) + " " + m.containsKey("n10") + " " + m.remove("n11", null); });
        t(name + " null key", () -> m.getOrDefault(null, 5) + " " + m.putIfAbsent(null, 5) + " " + m.computeIfAbsent(null, k -> 6) + " " + m.merge(null, 1, Integer::sum) + " " + m.get(null));
        t(name + " function modifies map", () -> m.computeIfAbsent("mod1", k -> { m.put("mod2", 1); return 2; }) + " " + m.containsKey("mod1") + m.containsKey("mod2"));
        t(name + " forEach modifies map", () -> { int[] c = {0}; m.forEach((k, v) -> { if (c[0]++ == 1) m.put("late", 1); }); return c[0]; });
    }

    public static void main(String[] args) {
        run("HashMap", new HashMap<>(), true);
        run("LinkedHashMap", new LinkedHashMap<>(), true);
        run("LinkedHashMap access", new LinkedHashMap<String, Integer>(16, 0.75f, true), true);
        run("TreeMap", new TreeMap<>(), false);
        run("TreeMap nullsFirst", new TreeMap<String, Integer>(Comparator.nullsFirst(Comparator.<String>naturalOrder())), true);
        run("Hashtable", new Hashtable<>(), false);
        run("Simple", new Simple<>(), true);
        run("synchronized", Collections.synchronizedMap(new HashMap<String, Integer>()), true);
        Map<String, Integer> um = Collections.unmodifiableMap(new HashMap<>(Collections.singletonMap("k", 1)));
        t("unmodifiable getOrDefault", () -> um.getOrDefault("k", 0) + " " + um.getOrDefault("x", 0));
        t("unmodifiable forEach", () -> { StringBuilder sb = new StringBuilder(); um.forEach((k, v) -> sb.append(k).append(v)); return sb; });
        t("unmodifiable putIfAbsent", () -> um.putIfAbsent("k", 5));
        t("unmodifiable remove2", () -> um.remove("k", 1));
        t("unmodifiable replace", () -> um.replace("k", 1));
        t("unmodifiable replace3", () -> um.replace("k", 1, 2));
        t("unmodifiable computeIfAbsent", () -> um.computeIfAbsent("k", k -> 3));
        t("unmodifiable computeIfPresent", () -> um.computeIfPresent("k", (k, v) -> 3));
        t("unmodifiable compute", () -> um.compute("k", (k, v) -> 3));
        t("unmodifiable merge", () -> um.merge("k", 1, Integer::sum));
        t("unmodifiable replaceAll", () -> { um.replaceAll((k, v) -> v); return 0; });
        Map<String, Integer> one = Collections.singletonMap("s", 1);
        t("singletonMap", () -> one.getOrDefault("s", 0) + " " + one.getOrDefault("t", 0));
        t("singletonMap putIfAbsent", () -> one.putIfAbsent("s", 1));
        t("singletonMap compute", () -> one.compute("s", (k, v) -> 2));
        t("singletonMap forEach", () -> { StringBuilder sb = new StringBuilder(); one.forEach((k, v) -> sb.append(k).append(v)); return sb; });
        Map<String, Integer> none = Collections.emptyMap();
        t("emptyMap", () -> none.getOrDefault("s", 7) + " " + none.size() + " " + none);
        t("emptyMap forEach", () -> { none.forEach((k, v) -> { throw new IllegalStateException(); }); return "ok"; });
        t("emptyMap forEach null", () -> { none.forEach(null); return 0; });
        t("emptyMap replaceAll null", () -> { none.replaceAll(null); return 0; });
        t("emptyMap putIfAbsent", () -> none.putIfAbsent("a", 1));
        t("emptyMap computeIfAbsent", () -> none.computeIfAbsent("a", k -> 1));
        t("emptyMap merge", () -> none.merge("a", 1, Integer::sum));
        t("entry comparators", () -> { List<Map.Entry<String, Integer>> es = new ArrayList<>(); es.add(new AbstractMap.SimpleEntry<>("b", 2)); es.add(new AbstractMap.SimpleEntry<>("a", 3)); es.add(new AbstractMap.SimpleEntry<>("c", 1));
            StringBuilder sb = new StringBuilder(); es.sort(Map.Entry.comparingByKey()); sb.append(es); es.sort(Map.Entry.comparingByValue()); sb.append(es);
            es.sort(Map.Entry.<String, Integer>comparingByKey(Comparator.reverseOrder())); sb.append(es); es.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())); return sb.append(es); });
        t("entry comparators null", () -> Map.Entry.<String, Integer>comparingByKey(null));
        t("entry comparators null value", () -> Map.Entry.<String, Integer>comparingByValue(null));
        t("simple entry", () -> { Map.Entry<String, Integer> e = new AbstractMap.SimpleEntry<>("k", 1); Map.Entry<String, Integer> i = new AbstractMap.SimpleImmutableEntry<>("k", 1); return e + " " + i + " " + e.equals(i) + " " + (e.hashCode() == i.hashCode()) + " " + e.setValue(5) + e; });
        t("immutable entry set", () -> new AbstractMap.SimpleImmutableEntry<>("k", 1).setValue(2));
        t("entry null parts", () -> new AbstractMap.SimpleEntry<String, String>(null, null) + " " + new AbstractMap.SimpleEntry<String, String>(null, null).hashCode());
        System.out.println("done");
    }
}
