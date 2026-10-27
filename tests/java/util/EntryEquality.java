package util;

import java.util.*;
import java.util.function.*;

/** Which equals each map calls, and on which side (spec 8.C3); the fixed maps' views (spec 10.X7). */
public class EntryEquality {
    static final StringBuilder log = new StringBuilder();

    /** Logs every equals call; a name ending in '!' is not equal even to itself. */
    static final class W implements Comparable<W> {
        final String n;
        W(String n) { this.n = n; }
        public boolean equals(Object o) {
            log.append(n).append(".eq(").append(o).append(") ");
            return o instanceof W && ((W) o).n.equals(n) && !n.endsWith("!");
        }
        public int hashCode() { return n.charAt(0); }
        public int compareTo(W o) { return n.compareTo(o.n); }
        public String toString() { return n; }
    }

    static void t(String label, Supplier<Object> s) {
        log.setLength(0);
        String r;
        try { r = String.valueOf(s.get()); } catch (RuntimeException e) { r = "!" + e.getClass().getSimpleName(); }
        System.out.println(label + " = " + r + "   [" + log + "]");
    }

    public static void main(String[] args) {
        W bad = new W("x!"), k = new W("k"), k2 = new W("k"), v = new W("v"), v2 = new W("v");
        List<Map<W, W>> maps = Arrays.asList(new HashMap<>(), new LinkedHashMap<>(), new TreeMap<>(), new Hashtable<>());
        for (Map<W, W> m : maps) {
            String n = m.getClass().getSimpleName();
            m.put(k, v);
            m.put(bad, bad);
            Map.Entry<W, W> held = null;
            for (Map.Entry<W, W> e : m.entrySet()) if (e.getKey() == bad) held = e;
            final Map.Entry<W, W> eb = held;
            t(n + " entry equals same objects", () -> eb.equals(new AbstractMap.SimpleEntry<>(bad, bad)));
            t(n + " entry equals itself", () -> eb.equals(eb));
            t(n + " entrySet contains same objects", () -> m.entrySet().contains(new AbstractMap.SimpleEntry<>(bad, bad)));
            t(n + " entrySet contains copies", () -> m.entrySet().contains(new AbstractMap.SimpleEntry<>(k2, v2)));
            t(n + " key lookups", () -> m.containsKey(bad) + " " + m.get(bad) + " " + m.keySet().contains(bad));
            t(n + " containsValue copy", () -> m.containsValue(v2));
            t(n + " values contains copy", () -> m.values().contains(v2));
            t(n + " values remove copy", () -> m.values().remove(v2) + " " + m.put(k, v));
            t(n + " entrySet remove copies", () -> m.entrySet().remove(new AbstractMap.SimpleEntry<>(k2, v2)) + " " + m.put(k, v));
            t(n + " equals a HashMap copy", () -> m.equals(new HashMap<>(m)));
        }
        Hashtable<W, W> ht = new Hashtable<>();
        ht.put(k, v);
        ht.put(bad, bad);
        t("Hashtable contains copy", () -> ht.contains(v2));
        TreeMap<String, W> tv = new TreeMap<>();
        tv.put("a", bad);
        tv.put("b", v);
        t("TreeMap values remove, a value unequal to itself first", () -> tv.values().remove(v2) + " " + tv);
        HashMap<String, W> hv = new HashMap<>(tv);
        hv.put("b", v);
        t("HashMap values remove, a value unequal to itself first", () -> hv.values().remove(v2) + " " + hv.size());
        HashMap<W, W> hm = new HashMap<>();
        hm.put(bad, bad);
        t("unmodifiable entrySet contains same objects", () -> Collections.unmodifiableMap(hm).entrySet().contains(new AbstractMap.SimpleEntry<>(bad, bad)));
        t("checked entrySet contains same objects", () -> Collections.checkedMap(hm, W.class, W.class).entrySet().contains(new AbstractMap.SimpleEntry<>(bad, bad)));
        t("HashMap remove by the same key", () -> new HashMap<>(hm).remove(bad));
        IdentityHashMap<W, W> im = new IdentityHashMap<>();
        im.put(bad, v);
        t("IdentityHashMap equals a HashMap copy", () -> im.equals(new HashMap<>(im)) + " " + new HashMap<>(im).equals(im));
        t("emptyMap views", () -> {
            Map<Object, Object> e = Collections.emptyMap();
            return ((Object) e.keySet() == Collections.emptySet()) + " " + ((Object) e.values() == Collections.emptySet()) + " "
                + ((Object) e.entrySet() == Collections.emptySet()) + " " + ((Object) e.keySet().iterator() == Collections.emptyIterator()) + " "
                + ((Object) e.values().iterator() == Collections.emptyIterator()) + " " + e.values().hashCode();
        });
        t("emptyList and emptySet iterators", () -> ((Object) Collections.emptyList().iterator() == Collections.emptyIterator()) + " "
            + ((Object) Collections.emptyList().listIterator() == Collections.emptyListIterator()) + " " + ((Object) Collections.emptySet().iterator() == Collections.emptyIterator()));
        t("singletonMap views", () -> {
            Map<Integer, Integer> s = Collections.singletonMap(1, 2);
            Class<?> one = Collections.singleton(1).getClass();
            return (s.keySet().getClass() == one) + " " + (s.values().getClass() == one) + " " + (s.entrySet().getClass() == one) + " "
                + (s.keySet() == s.keySet()) + " " + (s.values() == s.values()) + " " + (s.entrySet() == s.entrySet()) + " " + s.values().hashCode();
        });
        System.out.println("done");
    }
}
