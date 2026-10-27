package util;

import java.util.*;
import java.util.function.*;

/** Collections.checked* refuse elements of the wrong type; synchronizedNavigable* behave like the original. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CheckedViews {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        List list = Collections.checkedList(new ArrayList<Integer>(), Integer.class);
        t("list add", () -> list.add(1) + " " + list.add(null) + " " + list);
        t("list add bad", () -> list.add("x"));
        t("list add@ bad", () -> { list.add(0, 2.5); return 0; });
        t("list set bad", () -> list.set(0, 'c'));
        t("list addAll bad", () -> list.addAll(Arrays.asList(5, "six", 7)));
        t("list addAll@ bad", () -> list.addAll(0, Arrays.asList(5L)));
        t("list unchanged", () -> list);
        t("list replaceAll bad", () -> { list.replaceAll(x -> x == null ? "n" : x); return list; });
        t("list listIterator", () -> { ListIterator it = list.listIterator(); it.next(); it.set(9); it.add(10); try { it.add("z"); } catch (ClassCastException e) { return list + " " + e.getMessage(); } return "no exception"; });
        t("list subList", () -> { List sub = list.subList(0, 1); sub.add(3); sub.add("no"); return sub; });
        t("list sort/equals", () -> { List c = Collections.checkedList(new LinkedList<Integer>(Arrays.asList(3, 1, 2)), Integer.class); c.sort(null); return c + " " + c.equals(Arrays.asList(1, 2, 3)) + " " + (c.hashCode() == Arrays.asList(1, 2, 3).hashCode()) + " " + (c instanceof RandomAccess) + " " + (list instanceof RandomAccess); });
        t("set", () -> { Set s = Collections.checkedSet(new LinkedHashSet<Integer>(), Integer.class); s.add(1); s.add(1); s.addAll(Arrays.asList(2, 3)); try { s.add("x"); } catch (ClassCastException e) { return s + " " + e.getMessage(); } return s; });
        t("queue", () -> { Queue q = Collections.checkedQueue(new LinkedList<Integer>(), Integer.class); q.offer(1); q.add(2); try { q.offer("x"); } catch (ClassCastException e) { return q + " " + q.poll() + " " + q.peek(); } return q; });
        t("collection", () -> { Collection c = Collections.checkedCollection(new ArrayList<Integer>(), Integer.class); c.add(1); c.addAll(Arrays.asList(2, 3)); c.removeIf(x -> x.equals(2)); try { c.add(1L); } catch (ClassCastException e) { return c + " " + e.getMessage(); } return c; });
        t("sortedSet", () -> { SortedSet s = Collections.checkedSortedSet(new TreeSet<Integer>(), Integer.class); s.add(3); s.add(1); try { s.headSet(3).add("x"); } catch (ClassCastException e) { return s + " " + s.first() + " " + s.last() + " " + s.headSet(3) + " " + e.getMessage(); } return s; });
        t("navigableSet", () -> { NavigableSet s = Collections.checkedNavigableSet(new TreeSet<Integer>(Arrays.asList(1, 5, 9)), Integer.class); try { s.descendingSet().add("x"); } catch (ClassCastException e) { return s.floor(6) + " " + s.descendingSet() + " " + s.subSet(1, true, 9, false) + " " + s.pollFirst() + " " + e.getMessage(); } return s; });
        Map map = Collections.checkedMap(new LinkedHashMap<String, Integer>(), String.class, Integer.class);
        t("map put", () -> map.put("a", 1) + " " + map.put(null, null) + " " + map);
        t("map put bad key", () -> map.put(1, 1));
        t("map put bad value", () -> map.put("b", "c"));
        t("map putAll bad", () -> { Map src = new LinkedHashMap(); src.put("ok", 1); src.put("bad", "x"); map.putAll(src); return map; });
        t("map putIfAbsent bad", () -> map.putIfAbsent("q", 2.0));
        t("map replace bad", () -> map.replace("a", 'c'));
        t("map replace3 bad", () -> map.replace("a", 1, "x"));
        t("map replaceAll bad", () -> { map.replaceAll((k, v) -> "str"); return map; });
        t("map computeIfAbsent bad", () -> map.computeIfAbsent("new", k -> "bad"));
        t("map computeIfPresent bad", () -> map.computeIfPresent("a", (k, v) -> "bad"));
        t("map compute bad", () -> map.compute("a", (k, v) -> 1L));
        t("map merge bad", () -> map.merge("a", 5, (x, y) -> "bad"));
        t("map merge ok", () -> map.merge("a", 5, (x, y) -> (Integer) x + (Integer) y) + " " + map);
        t("map entry setValue", () -> { Map.Entry e = (Map.Entry) map.entrySet().iterator().next(); e.setValue(7); try { e.setValue("x"); } catch (ClassCastException ex) { return map + " " + ex.getMessage(); } return map; });
        t("map entries toArray", () -> { Object[] a = map.entrySet().toArray(); ((Map.Entry) a[0]).setValue(8); try { ((Map.Entry) a[0]).setValue("x"); } catch (ClassCastException ex) { return a.length + " " + map + " " + map.entrySet().contains(new AbstractMap.SimpleEntry("a", 8)); } return a.length; });
        t("map views", () -> map.keySet() + " " + map.values() + " " + map.equals(new HashMap(map)) + " " + map.size());
        t("sortedMap", () -> { SortedMap m = Collections.checkedSortedMap(new TreeMap<String, Integer>(), String.class, Integer.class); m.put("b", 2); m.put("a", 1); try { m.headMap("b").put(5, 1); } catch (ClassCastException e) { return m + " " + m.firstKey() + " " + m.headMap("b") + " " + e.getMessage(); } return m; });
        t("navigableMap", () -> { NavigableMap m = Collections.checkedNavigableMap(new TreeMap<String, Integer>(), String.class, Integer.class); m.put("b", 2); m.put("a", 1); try { m.descendingMap().put("c", "x"); } catch (ClassCastException e) { Map.Entry f = m.firstEntry(); try { f.setValue("bad"); } catch (ClassCastException e2) { return m + " " + f + " " + m.floorKey("az") + " " + m.pollFirstEntry() + " " + e.getMessage() + " | " + e2.getMessage(); } } return m; });
        t("null list", () -> Collections.checkedList(null, Integer.class));
        t("null type", () -> Collections.checkedList(new ArrayList<Integer>(), null));
        t("null key type", () -> Collections.checkedMap(new HashMap<String, Integer>(), null, Integer.class));
        t("wrong existing element", () -> { List raw = new ArrayList(Arrays.asList("already")); List c = Collections.checkedList(raw, Integer.class); return c + " " + c.get(0); });
        NavigableSet<Integer> ns = Collections.synchronizedNavigableSet(new TreeSet<Integer>(Arrays.asList(5, 15, 25, 35)));
        t("sync navigableSet", () -> ns + " " + ns.lower(15) + " " + ns.floor(15) + " " + ns.ceiling(16) + " " + ns.higher(35) + " " + ns.headSet(25) + ns.tailSet(15, false) + ns.subSet(5, 26) + ns.subSet(5, false, 25, true) + ns.descendingSet() + " " + ns.pollFirst() + ns.pollLast() + ns);
        NavigableMap<Integer, String> nm = Collections.synchronizedNavigableMap(new TreeMap<Integer, String>());
        t("sync navigableMap", () -> { for (int i = 1; i <= 5; i++) nm.put(i * 10, "v" + i); return nm + " " + nm.firstKey() + " " + nm.lowerEntry(30) + " " + nm.ceilingKey(31) + " " + nm.headMap(30) + nm.tailMap(30, false) + nm.subMap(10, 40) + nm.descendingMap() + nm.navigableKeySet() + nm.descendingKeySet() + " " + nm.pollFirstEntry() + nm.pollLastEntry() + nm; });
        t("sync null", () -> Collections.synchronizedNavigableMap(null));
        t("merge of an absent key is not type-checked", () -> { Map c = Collections.checkedMap(new LinkedHashMap<String, Integer>(), String.class, Integer.class); Object a = c.merge("x", "str", (p, q) -> p); Object b = c.merge(7, 7, (p, q) -> p); Object d = c.merge("y", 5, (p, q) -> "never called"); return a + " " + b + " " + d + " " + c; });
        t("merge of a present key checks the result", () -> { Map c = Collections.checkedMap(new LinkedHashMap<String, Integer>(), String.class, Integer.class); c.put("x", 1); return c.merge("x", 2, (p, q) -> "str"); });
        t("computeIfAbsent checks", () -> { Map c = Collections.checkedMap(new HashMap<String, Integer>(), String.class, Integer.class); return c.computeIfAbsent("x", k -> "s"); });
        t("compute on absent checks", () -> { Map c = Collections.checkedMap(new HashMap<String, Integer>(), String.class, Integer.class); return c.compute("x", (k, w) -> "s"); });
        System.out.println("done");
    }
}
