package util;

import java.util.*;
import java.util.function.*;

/** TreeMap navigation, views and ranges: sub/head/tail/descending maps, key sets, exceptions. */
public class TreeMaps {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static TreeMap<Integer, String> tm(int... keys) {
        TreeMap<Integer, String> m = new TreeMap<>();
        for (int k : keys) m.put(k, "v" + k);
        return m;
    }

    public static void main(String[] args) {
        TreeMap<Integer, String> m = tm(50, 20, 80, 10, 30, 60, 90, 40, 70);
        System.out.println(m + " " + m.size() + " " + m.firstKey() + " " + m.lastKey() + " " + m.comparator());
        for (int k : new int[]{5, 10, 35, 90, 95}) {
            System.out.println(k + ": " + m.lowerKey(k) + " " + m.floorKey(k) + " " + m.ceilingKey(k) + " " + m.higherKey(k)
                + " | " + m.lowerEntry(k) + " " + m.floorEntry(k) + " " + m.ceilingEntry(k) + " " + m.higherEntry(k));
        }
        System.out.println(m.firstEntry() + " " + m.lastEntry() + " " + m.get(30) + " " + m.get(31) + " " + m.containsKey(30) + " " + m.containsValue("v30"));
        System.out.println(m.headMap(40) + " " + m.headMap(40, true) + " " + m.tailMap(70) + " " + m.tailMap(70, false) + " " + m.subMap(20, 60) + " " + m.subMap(20, false, 60, true));
        System.out.println(m.descendingMap() + " " + m.descendingKeySet() + " " + m.navigableKeySet() + " " + m.keySet() + " " + m.values());
        NavigableMap<Integer, String> sub = m.subMap(20, true, 70, false);
        System.out.println(sub + " " + sub.size() + " " + sub.firstKey() + " " + sub.lastKey() + " " + sub.floorKey(100) + " " + sub.ceilingKey(0) + " " + sub.lowerKey(20) + " " + sub.higherKey(60));
        sub.put(25, "v25"); sub.remove(30); sub.put(65, "v65");
        System.out.println(sub + " " + m);
        t("sub put out of range", () -> sub.put(70, "x"));
        t("sub put low", () -> sub.put(19, "x"));
        t("sub get out of range", () -> sub.get(80) + " " + sub.containsKey(10) + " " + sub.remove(10));
        t("sub of sub ok", () -> sub.subMap(25, true, 40, true));
        t("sub of sub bad", () -> sub.subMap(10, true, 40, true));
        t("sub of sub bad hi", () -> sub.headMap(80, false));
        t("sub of sub bad lo", () -> sub.tailMap(10));
        t("subMap reversed", () -> m.subMap(60, 20));
        t("subMap equal", () -> m.subMap(20, 20) + " " + m.subMap(20, true, 20, true) + " " + m.subMap(20, false, 20, false));
        NavigableMap<Integer, String> desc = m.descendingMap();
        System.out.println(desc.firstKey() + " " + desc.lastKey() + " " + desc.headMap(60) + " " + desc.tailMap(60) + " " + desc.subMap(80, 40) + " " + desc.lowerKey(60) + " " + desc.higherKey(60) + " " + desc.floorKey(65) + " " + desc.ceilingKey(65));
        System.out.println(desc.descendingMap() + " " + desc.comparator().compare(1, 2) + " " + desc.navigableKeySet() + " " + desc.descendingKeySet());
        t("desc subMap bad", () -> desc.subMap(40, 80));
        t("desc put", () -> desc.put(55, "v55") + " " + m.get(55));
        t("pollFirst", () -> m.pollFirstEntry() + " " + m.pollLastEntry() + " " + m.size());
        t("poll on sub", () -> sub.pollFirstEntry() + " " + sub.pollLastEntry() + " " + sub + " " + m);
        t("poll empty", () -> new TreeMap<Integer, String>().pollFirstEntry() + " " + new TreeMap<Integer, String>().pollLastEntry());
        t("first empty", () -> new TreeMap<Integer, String>().firstKey());
        t("last empty", () -> new TreeMap<Integer, String>().lastKey());
        t("firstEntry empty", () -> new TreeMap<Integer, String>().firstEntry() + " " + new TreeMap<Integer, String>().floorKey(1));
        t("entry immutable", () -> { Map.Entry<Integer, String> e = m.firstEntry(); e.setValue("x"); return e; });
        t("entry live in iteration", () -> { for (Map.Entry<Integer, String> e : m.entrySet()) e.setValue(e.getValue() + "!"); return m; });
        t("null key", () -> m.put(null, "x"));
        t("null get", () -> m.get(null));
        t("null containsKey", () -> m.containsKey(null));
        t("null remove", () -> m.remove(null));
        t("not comparable", () -> new TreeMap<Object, Object>().put(new Object(), 1));
        t("not comparable 2nd", () -> { TreeMap<Object, Object> o = new TreeMap<>(); o.put("a", 1); return o.put(2, 1); });
        t("null in comparator map", () -> { TreeMap<String, Integer> o = new TreeMap<>(Comparator.nullsFirst(Comparator.<String>naturalOrder())); o.put("b", 1); o.put(null, 2); o.put("a", 3); return o + " " + o.get(null) + " " + o.firstKey(); });
        t("reverse comparator", () -> { TreeMap<String, Integer> o = new TreeMap<>(Collections.reverseOrder()); o.put("b", 1); o.put("c", 2); o.put("a", 3); return o + " " + o.headMap("b") + " " + o.ceilingKey("bb") + " " + o.descendingMap(); });
        t("case insensitive", () -> { TreeMap<String, Integer> o = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); o.put("Apple", 1); o.put("apple", 2); o.put("BANANA", 3); return o + " " + o.get("banana"); });
        t("copy sorted", () -> { TreeMap<String, Integer> o = new TreeMap<>(Collections.reverseOrder()); o.put("a", 1); o.put("b", 2); return new TreeMap<String, Integer>(o) + " " + new TreeMap<String, Integer>((Map<String, Integer>) o); });
        t("putAll", () -> { TreeMap<Integer, String> o = tm(1); o.putAll(tm(5, 3)); o.putAll(new HashMap<Integer, String>()); return o; });
        t("clone", () -> { TreeMap<Integer, String> o = tm(3, 1); @SuppressWarnings("unchecked") TreeMap<Integer, String> c = (TreeMap<Integer, String>) o.clone(); c.put(2, "x"); return o + " " + c; });
        t("keySet ops", () -> { NavigableSet<Integer> ks = m.navigableKeySet(); return ks.first() + " " + ks.last() + " " + ks.lower(50) + " " + ks.higher(50) + " " + ks.headSet(50) + ks.tailSet(50) + ks.subSet(20, 60) + ks.descendingSet() + ks.pollFirst() + " " + m; });
        t("keySet remove", () -> { Set<Integer> ks = m.keySet(); return ks.remove(40) + " " + ks.remove(41) + " " + ks.contains(50) + " " + m; });
        t("values remove", () -> { m.values().remove("v60!"); m.values().removeIf(v -> v.startsWith("v7")); return m.values() + " " + m; });
        t("entrySet remove", () -> { Set<Map.Entry<Integer, String>> es = m.entrySet(); return es.remove(new AbstractMap.SimpleEntry<Integer, String>(50, "v50!")) + " " + es.remove(new AbstractMap.SimpleEntry<Integer, String>(50, "no")) + " " + es.contains(new AbstractMap.SimpleEntry<Integer, String>(80, "v80!")) + " " + m; });
        t("iterator remove", () -> { TreeMap<Integer, String> o = tm(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); Iterator<Integer> it = o.keySet().iterator(); while (it.hasNext()) if (it.next() % 3 != 0) it.remove(); return o; });
        t("descending iterator remove", () -> { TreeMap<Integer, String> o = tm(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); Iterator<Integer> it = o.descendingKeySet().iterator(); while (it.hasNext()) if (it.next() % 3 == 0) it.remove(); return o; });
        t("sub iterator remove", () -> { TreeMap<Integer, String> o = tm(1, 2, 3, 4, 5, 6, 7, 8, 9, 10); Iterator<Map.Entry<Integer, String>> it = o.subMap(3, 8).entrySet().iterator(); while (it.hasNext()) if (it.next().getKey() % 2 == 1) it.remove(); return o; });
        t("CME", () -> { TreeMap<Integer, String> o = tm(1, 2, 3); for (Integer k : o.keySet()) o.put(k + 10, "x"); return o; });
        t("CME remove ok last", () -> { TreeMap<Integer, String> o = tm(1, 2, 3); for (Integer k : o.keySet()) if (k == 3) o.remove(k); return o; });
        t("forEach CME", () -> { TreeMap<Integer, String> o = tm(1, 2, 3); o.forEach((k, v) -> o.put(k + 10, "x")); return o; });
        t("sub size after change", () -> { TreeMap<Integer, String> o = tm(1, 2, 3, 4); NavigableMap<Integer, String> s = o.headMap(3, true); int a = s.size(); o.put(0, "z"); return a + " " + s.size() + " " + s.isEmpty(); });
        t("empty views", () -> { TreeMap<Integer, String> o = new TreeMap<>(); return o + " " + o.keySet() + o.values() + o.entrySet() + o.descendingMap() + o.headMap(1) + o.isEmpty(); });
        t("hashCode/equals", () -> tm(1, 2).equals(new HashMap<Integer, String>(tm(1, 2))) + " " + tm(1, 2).hashCode() + " " + tm(1, 2).keySet().hashCode());
        t("many", () -> { TreeMap<Integer, Integer> o = new TreeMap<>(); for (int i = 0; i < 2000; i++) o.put((i * 7919) % 2003, i); for (int i = 0; i < 2000; i += 3) o.remove((i * 7919) % 2003); long s = 0; for (int k : o.keySet()) s = s * 31 + k; return o.size() + " " + o.firstKey() + " " + o.lastKey() + " " + s; });
        System.out.println("done");
    }
}
