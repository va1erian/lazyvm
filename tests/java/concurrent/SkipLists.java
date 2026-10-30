package concurrent;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

/** ConcurrentSkipListMap and ConcurrentSkipListSet: sorted order, nulls, ranges, views, entries. */
public class SkipLists {
    interface Body { Object run() throws Throwable; }

    static String n(Object o) { return String.valueOf(o).replaceAll("@[0-9a-f]+", "@H"); }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + n(body.run())); }
        catch (Throwable t) { System.out.println(label + " ! " + n(t)); }
    }

    static ConcurrentSkipListMap<Integer, String> map() {
        ConcurrentSkipListMap<Integer, String> m = new ConcurrentSkipListMap<>();
        for (int k : new int[] {50, 10, 30, 40, 20}) m.put(k, "v" + k);
        return m;
    }

    static void mapBasics() {
        ConcurrentSkipListMap<Integer, String> e = new ConcurrentSkipListMap<>();
        show("empty", () -> e);
        show("firstKey empty", e::firstKey);
        show("lastKey empty", e::lastKey);
        show("firstEntry empty", e::firstEntry);
        show("lastEntry empty", e::lastEntry);
        show("pollFirst empty", e::pollFirstEntry);
        show("pollLast empty", e::pollLastEntry);
        show("comparator", e::comparator);
        Comparator<Integer> rev = Comparator.reverseOrder();
        show("given comparator", () -> new ConcurrentSkipListMap<Integer, String>(rev).comparator() == rev);
        show("null comparator ok", () -> new ConcurrentSkipListMap<Integer, String>((Comparator<Integer>) null));
        show("null map", () -> new ConcurrentSkipListMap<Integer, String>((Map<Integer, String>) null));
        show("null sorted", () -> new ConcurrentSkipListMap<Integer, String>((SortedMap<Integer, String>) null));
        show("from sorted keeps comparator", () -> {
            TreeMap<Integer, String> t = new TreeMap<>(rev);
            t.put(1, "a");
            t.put(2, "b");
            ConcurrentSkipListMap<Integer, String> c = new ConcurrentSkipListMap<>(t);
            return c + " " + (c.comparator() == rev);
        });
        show("from map", () -> new ConcurrentSkipListMap<>(Collections.singletonMap(1, "a")));
        ConcurrentSkipListMap<Integer, String> m = map();
        show("sorted", () -> m);
        show("put null key", () -> m.put(null, "x"));
        show("put null value", () -> m.put(1, null));
        show("get null", () -> m.get(null));
        show("containsKey null", () -> m.containsKey(null));
        show("containsValue null", () -> m.containsValue(null));
        show("remove null", () -> m.remove(null));
        show("remove pair null key", () -> m.remove(null, "x"));
        show("remove pair null value", () -> m.remove(10, null));
        show("putIfAbsent null value", () -> m.putIfAbsent(1, null));
        show("replace null value", () -> m.replace(10, null));
        show("replace3 null", () -> m.replace(10, null, "x"));
        show("getOrDefault null key", () -> m.getOrDefault(null, "x"));
        show("compute null fn", () -> m.compute(10, null));
        show("merge null value", () -> m.merge(10, null, String::concat));
        show("merge null fn", () -> m.merge(10, "x", null));
        show("lowerKey null", () -> m.lowerKey(null));
        show("ceilingEntry null", () -> m.ceilingEntry(null));
        show("forEach null", () -> { m.forEach(null); return "no"; });
        show("replaceAll null", () -> { m.replaceAll(null); return "no"; });
        show("replaceAll to null", () -> { m.replaceAll((k, v) -> null); return "no"; });
        show("mixed key", () -> {
            ConcurrentSkipListMap<Object, String> o = new ConcurrentSkipListMap<>();
            o.put("s", "x");
            return o.put(1, "y");
        });
        show("mixed get", () -> {
            ConcurrentSkipListMap<Object, String> o = new ConcurrentSkipListMap<>();
            o.put("s", "x");
            return o.get(1);
        });
        show("put old", () -> m.put(10, "new"));
        show("get", () -> m.get(10));
        show("putIfAbsent", () -> m.putIfAbsent(10, "x"));
        show("replace", () -> m.replace(10, "v10"));
        show("replace absent", () -> m.replace(1, "x"));
        show("replace3", () -> m.replace(10, "v10", "w10"));
        show("replace3 wrong", () -> m.replace(10, "v10", "z"));
        show("remove pair", () -> m.remove(10, "w10"));
        show("computeIfAbsent", () -> m.computeIfAbsent(10, k -> "c" + k));
        show("computeIfPresent", () -> m.computeIfPresent(10, (k, v) -> v + "!"));
        show("compute", () -> m.compute(10, (k, v) -> v + "?"));
        show("compute removes", () -> m.compute(10, (k, v) -> null));
        show("merge new", () -> m.merge(10, "a", String::concat));
        show("merge old", () -> m.merge(10, "b", String::concat));
        show("merge removes", () -> m.merge(10, "c", (x, y) -> null));
        m.put(10, "v10");
        show("getOrDefault", () -> m.getOrDefault(11, "d"));
        show("size", m::size);
        show("containsValue", () -> m.containsValue("v30"));
        List<String> seen = new ArrayList<>();
        m.forEach((k, v) -> seen.add(k + v));
        show("forEach in order", () -> seen);
        show("equals", () -> m.equals(new HashMap<>(m)));
        show("hash", () -> m.hashCode() == new HashMap<>(m).hashCode());
        @SuppressWarnings("unchecked") ConcurrentSkipListMap<Integer, String> c = m.clone();
        c.clear();
        show("clone independent", () -> m.size() + " " + c.size());
    }

    static void navigation() {
        ConcurrentSkipListMap<Integer, String> m = map();
        show("firstKey", m::firstKey);
        show("lastKey", m::lastKey);
        show("firstEntry", m::firstEntry);
        show("lastEntry", m::lastEntry);
        show("lower", () -> m.lowerKey(30) + " " + m.lowerEntry(30) + " " + m.lowerKey(10));
        show("floor", () -> m.floorKey(30) + " " + m.floorEntry(35) + " " + m.floorKey(5));
        show("ceiling", () -> m.ceilingKey(30) + " " + m.ceilingEntry(35) + " " + m.ceilingKey(55));
        show("higher", () -> m.higherKey(30) + " " + m.higherEntry(30) + " " + m.higherKey(50));
        Map.Entry<Integer, String> e = m.firstEntry();
        show("entry immutable", () -> e.setValue("x"));
        show("snapshot entry", () -> { m.put(10, "changed"); String s = e.getValue(); m.put(10, "v10"); return s; });
        show("pollFirst", m::pollFirstEntry);
        show("pollLast", m::pollLastEntry);
        show("after polls", () -> m);
        Iterator<Map.Entry<Integer, String>> it = m.entrySet().iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        Map.Entry<Integer, String> live = it.next();
        show("iterator entry setValue", () -> live.setValue("x"));
        it.remove();
        show("iterator remove", () -> m);
        show("remove twice", () -> { it.remove(); return "no"; });
        m.put(1, "a");
        show("no CME", () -> { while (it.hasNext()) it.next(); return "ok"; });
        show("past end", it::next);
    }

    static void ranges() {
        ConcurrentSkipListMap<Integer, String> m = map();
        ConcurrentNavigableMap<Integer, String> sub = m.subMap(20, 40);
        show("sub", () -> sub);
        show("sub inclusive", () -> m.subMap(20, false, 40, true));
        show("head", () -> m.headMap(30));
        show("head inclusive", () -> m.headMap(30, true));
        show("tail", () -> m.tailMap(30));
        show("tail exclusive", () -> m.tailMap(30, false));
        show("bad range", () -> m.subMap(40, 20));
        show("null from", () -> m.subMap(null, 20));
        show("null to", () -> m.subMap(20, null));
        show("head null", () -> m.headMap(null));
        show("tail null", () -> m.tailMap(null));
        show("sub size", sub::size);
        show("sub first", sub::firstKey);
        show("sub last", sub::lastKey);
        show("sub out of range get", () -> sub.get(50));
        show("sub out of range contains", () -> sub.containsKey(50));
        show("sub out of range put", () -> sub.put(50, "x"));
        show("sub out of range remove", () -> sub.remove(50));
        show("sub out of range putIfAbsent", () -> sub.putIfAbsent(50, "x"));
        show("sub put within", () -> sub.put(25, "v25"));
        show("through", () -> m.containsKey(25));
        show("sub lower", () -> sub.lowerKey(20));
        show("sub ceiling", () -> sub.ceilingKey(35));
        show("sub higher past", () -> sub.higherKey(30));
        show("sub poll first", sub::pollFirstEntry);
        show("sub of sub", () -> sub.subMap(25, 30));
        show("sub of sub beyond", () -> sub.subMap(10, 30));
        show("sub head beyond", () -> sub.headMap(60));
        show("sub tail beyond", () -> sub.tailMap(0));
        show("sub clear scope", () -> { m.subMap(40, true, 40, true).clear(); return m; });
        ConcurrentSkipListMap<Integer, String> d = map();
        ConcurrentNavigableMap<Integer, String> desc = d.descendingMap();
        show("descending", () -> desc);
        show("descending comparator null source", () -> desc.comparator() != null);
        show("descending first", desc::firstKey);
        show("descending last", desc::lastKey);
        show("descending floor", () -> desc.floorKey(35));
        show("descending ceiling", () -> desc.ceilingKey(35));
        show("descending higher", () -> desc.higherKey(30));
        show("descending lower", () -> desc.lowerKey(30));
        show("descending poll", desc::pollFirstEntry);
        show("descending sub", () -> desc.subMap(40, 20));
        show("descending bad", () -> desc.subMap(20, 40));
        show("double descending", () -> desc.descendingMap());
        show("descending put", () -> desc.put(45, "v45"));
        show("seen in map", () -> d);
        show("empty head first", () -> d.headMap(1).firstKey());
        show("empty head entry", () -> d.headMap(1).firstEntry());
        show("empty head poll", () -> d.headMap(1).pollFirstEntry());
    }

    static void views() {
        ConcurrentSkipListMap<Integer, String> m = map();
        NavigableSet<Integer> keys = m.keySet();
        show("keySet", () -> keys);
        show("navigableKeySet same content", () -> m.navigableKeySet().equals(keys));
        show("descendingKeySet", m::descendingKeySet);
        show("values order", m::values);
        show("entries order", m::entrySet);
        show("key add", () -> keys.add(1));
        show("value add", () -> m.values().add("x"));
        show("entry add", () -> m.entrySet().add(new AbstractMap.SimpleEntry<>(1, "x")));
        show("key first", keys::first);
        show("key last", keys::last);
        show("key lower", () -> keys.lower(30));
        show("key pollFirst", keys::pollFirst);
        show("key pollLast", keys::pollLast);
        show("through", () -> m);
        show("key headSet", () -> keys.headSet(30));
        show("key tailSet", () -> keys.tailSet(30));
        show("key subSet", () -> keys.subSet(20, 40));
        show("key descendingSet", keys::descendingSet);
        show("key contains null", () -> keys.contains(null));
        show("key remove", () -> keys.remove(20));
        show("value remove", () -> m.values().remove("v30"));
        show("value contains null", () -> m.values().contains(null));
        show("entry contains", () -> m.entrySet().contains(new AbstractMap.SimpleEntry<>(40, "v40")));
        show("entry contains wrong", () -> m.entrySet().contains(new AbstractMap.SimpleEntry<>(40, "x")));
        show("entry contains non-entry", () -> ((Set<?>) m.entrySet()).contains("x"));
        show("entry remove", () -> m.entrySet().remove(new AbstractMap.SimpleEntry<>(40, "v40")));
        show("empty after", m::isEmpty);
        show("key first empty", keys::first);
    }

    static void sets() {
        ConcurrentSkipListSet<String> s = new ConcurrentSkipListSet<>();
        show("empty", () -> s);
        show("first empty", s::first);
        show("last empty", s::last);
        show("pollFirst empty", s::pollFirst);
        show("comparator", s::comparator);
        show("add null", () -> s.add(null));
        show("contains null", () -> s.contains(null));
        show("remove null", () -> s.remove(null));
        show("lower null", () -> s.lower(null));
        show("headSet null", () -> s.headSet(null));
        show("null collection", () -> new ConcurrentSkipListSet<String>((Collection<String>) null));
        show("null sorted", () -> new ConcurrentSkipListSet<String>((SortedSet<String>) null));
        show("null comparator ok", () -> new ConcurrentSkipListSet<String>((Comparator<String>) null));
        show("from sorted keeps comparator", () -> {
            TreeSet<String> t = new TreeSet<>(Comparator.reverseOrder());
            t.add("a");
            t.add("b");
            return new ConcurrentSkipListSet<>(t);
        });
        for (String v : new String[] {"d", "b", "e", "a", "c"}) s.add(v);
        show("sorted", () -> s);
        show("dup", () -> s.add("a"));
        show("size", s::size);
        show("first", s::first);
        show("last", s::last);
        show("lower", () -> s.lower("c") + s.lower("a"));
        show("floor", () -> s.floor("c") + s.floor("0"));
        show("ceiling", () -> s.ceiling("c") + s.ceiling("z"));
        show("higher", () -> s.higher("c") + s.higher("e"));
        show("headSet", () -> s.headSet("c"));
        show("headSet incl", () -> s.headSet("c", true));
        show("tailSet", () -> s.tailSet("c"));
        show("tailSet excl", () -> s.tailSet("c", false));
        show("subSet", () -> s.subSet("b", "d"));
        show("subSet flags", () -> s.subSet("b", false, "d", true));
        show("subSet bad", () -> s.subSet("d", "b"));
        show("subSet add outside", () -> s.subSet("b", "d").add("z"));
        show("descendingSet", s::descendingSet);
        List<String> desc = new ArrayList<>();
        for (Iterator<String> i = s.descendingIterator(); i.hasNext();) desc.add(i.next());
        show("descendingIterator", () -> desc);
        show("pollFirst", s::pollFirst);
        show("pollLast", s::pollLast);
        show("containsAll", () -> s.containsAll(Arrays.asList("b", "c")));
        show("removeAll", () -> s.removeAll(Arrays.asList("b", "zz")));
        show("removeAll null", () -> s.removeAll(null));
        show("equals", () -> s.equals(new HashSet<>(Arrays.asList("c", "d"))));
        show("equals other", () -> s.equals("x"));
        show("toArray", () -> Arrays.toString(s.toArray()));
        show("toArray typed", () -> Arrays.toString(s.toArray(new String[3])));
        ConcurrentSkipListSet<String> c = s.clone();
        c.clear();
        show("clone independent", () -> s.size() + " " + c.size());
        Iterator<String> it = s.iterator();
        show("remove before next", () -> { it.remove(); return "no"; });
        it.next();
        it.remove();
        show("iterator remove", () -> s);
        s.add("zz");
        show("no CME", () -> { while (it.hasNext()) it.next(); return "ok"; });
        show("past end", it::next);
        s.clear();
        show("clear", s::isEmpty);
    }

    public static void main(String[] args) {
        mapBasics();
        navigation();
        ranges();
        views();
        sets();
    }
}
