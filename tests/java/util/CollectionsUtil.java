package util;

import java.util.*;
import java.util.function.*;

/** The static methods of Collections: algorithms on lists, factories and the wrapper collections. */
public class CollectionsUtil {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static List<Integer> list(boolean linked, int... xs) {
        List<Integer> l = linked ? new LinkedList<Integer>() : new ArrayList<Integer>();
        for (int x : xs) l.add(x);
        return l;
    }

    public static void main(String[] args) {
        for (boolean linked : new boolean[]{false, true}) {
            String n = linked ? "linked " : "array ";
            List<Integer> a = list(linked, 5, 2, 9, 1, 7, 3, 8);
            Collections.sort(a); System.out.println(n + "sort " + a);
            System.out.println(n + "bsearch " + Collections.binarySearch(a, 7) + " " + Collections.binarySearch(a, 4) + " " + Collections.binarySearch(a, 0) + " " + Collections.binarySearch(a, 100));
            Collections.sort(a, Collections.reverseOrder()); System.out.println(n + "sort rev " + a + " " + Collections.binarySearch(a, 7, Collections.reverseOrder()) + " " + Collections.binarySearch(a, 4, Collections.<Integer>reverseOrder()));
            Collections.reverse(a); System.out.println(n + "reverse " + a);
            Collections.swap(a, 0, 6); System.out.println(n + "swap " + a);
            Collections.rotate(a, 2); System.out.println(n + "rotate 2 " + a);
            Collections.rotate(a, -3); System.out.println(n + "rotate -3 " + a);
            Collections.rotate(a, 100); System.out.println(n + "rotate 100 " + a);
            Collections.shuffle(a, new Random(42)); System.out.println(n + "shuffle 42 " + a);
            Collections.shuffle(a, new Random(7)); System.out.println(n + "shuffle 7 " + a);
            System.out.println(n + "min/max " + Collections.min(a) + " " + Collections.max(a) + " " + Collections.min(a, Collections.reverseOrder()) + " " + Collections.max(a, Collections.<Integer>reverseOrder()) + " " + Collections.min(a, null));
            System.out.println(n + "frequency " + Collections.frequency(a, 3) + " " + Collections.frequency(a, 99) + " " + Collections.frequency(Arrays.asList(null, 1, null), null));
            System.out.println(n + "replaceAll " + Collections.replaceAll(a, 3, 33) + " " + Collections.replaceAll(a, 3, 33) + " " + a);
            List<Integer> big = list(linked, 1, 2, 3, 1, 2, 3, 1, 2);
            System.out.println(n + "subList idx " + Collections.indexOfSubList(big, Arrays.asList(2, 3)) + " " + Collections.lastIndexOfSubList(big, Arrays.asList(2, 3)) + " " + Collections.indexOfSubList(big, Arrays.asList(3, 3)) + " " + Collections.indexOfSubList(big, new ArrayList<Integer>()) + " " + Collections.lastIndexOfSubList(big, new ArrayList<Integer>()) + " " + Collections.indexOfSubList(big, list(linked, 1, 2, 3, 1, 2, 3, 1, 2, 3)));
            List<Integer> dst = list(linked, 0, 0, 0, 0, 0);
            Collections.copy(dst, Arrays.asList(1, 2, 3)); System.out.println(n + "copy " + dst);
            Collections.fill(dst, 8); System.out.println(n + "fill " + dst);
            t(n + "copy too small", () -> { Collections.copy(list(linked, 1), Arrays.asList(1, 2)); return 0; });
            t(n + "min empty", () -> Collections.min(list(linked)));
            t(n + "max empty", () -> Collections.max(list(linked)));
            t(n + "swap bad", () -> { Collections.swap(list(linked, 1, 2), 0, 5); return 0; });
            t(n + "rotate empty", () -> { List<Integer> e = list(linked); Collections.rotate(e, 3); Collections.reverse(e); Collections.shuffle(e); Collections.sort(e); return e; });
        }
        List<Integer> long1 = new ArrayList<>();
        for (int i = 0; i < 200; i++) long1.add(i);
        List<Integer> long2 = new LinkedList<>(long1);
        Collections.shuffle(long1, new Random(1)); Collections.shuffle(long2, new Random(1));
        System.out.println(long1.equals(long2) + " " + long1.subList(0, 12));
        Collections.rotate(long2, 77); Collections.reverse(long2);
        System.out.println(long2.subList(0, 12));
        Collections.sort(long2);
        System.out.println(Collections.binarySearch(long2, 150) + " " + Collections.binarySearch(long2, 500) + " " + Collections.indexOfSubList(long2, Arrays.asList(100, 101, 102)));
        t("sort null element", () -> { List<Integer> l = new ArrayList<>(Arrays.asList(3, null, 1)); Collections.sort(l); return l; });
        t("sort not comparable", () -> { List<Object> l = new ArrayList<>(Arrays.asList(new Object(), new Object())); Collections.sort((List) l); return l; });
        t("sort stable", () -> { List<String> l = new ArrayList<>(Arrays.asList("bb", "a", "cc", "d", "ee", "f", "gg", "h", "ii", "j", "kk", "l")); Collections.sort(l, Comparator.comparingInt(String::length)); return l; });
        t("sort unmodifiable", () -> { Collections.sort(Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(2, 1)))); return 0; });
        t("sort asList", () -> { List<Integer> l = Arrays.asList(3, 1, 2); Collections.sort(l); return l; });
        t("nCopies", () -> Collections.nCopies(3, "ab") + " " + Collections.nCopies(0, "x") + " " + Collections.nCopies(4, null).indexOf(null) + " " + Collections.nCopies(3, 7).hashCode() + " " + Collections.nCopies(3, 7).equals(Arrays.asList(7, 7, 7)));
        t("nCopies bad", () -> Collections.nCopies(-2, 1));
        t("nCopies get bad", () -> Collections.nCopies(2, 1).get(2));
        t("nCopies add", () -> Collections.nCopies(2, 1).add(1));
        t("emptyList", () -> Collections.emptyList() + " " + Collections.emptyList().size() + " " + Collections.emptyList().equals(new ArrayList<Object>()) + " " + Collections.emptyList().hashCode() + " " + (Collections.emptyList() == Collections.EMPTY_LIST));
        t("emptyList iterator", () -> { Iterator<Object> it = Collections.emptyList().iterator(); return it.hasNext() + " " + it.next(); });
        t("emptyList iterator remove", () -> { Collections.emptyList().iterator().remove(); return 0; });
        t("emptyList get", () -> Collections.emptyList().get(0));
        t("emptySet/emptyMap", () -> Collections.emptySet() + " " + Collections.emptyMap() + " " + Collections.emptySet().contains(1) + " " + Collections.emptyMap().get(1) + " " + Collections.emptyMap().entrySet() + Collections.emptyMap().keySet() + Collections.emptyMap().values());
        t("emptySet add", () -> Collections.emptySet().add(1));
        t("emptyMap put", () -> Collections.emptyMap().put(1, 1));
        t("emptyMap equals", () -> Collections.emptyMap().equals(new HashMap<Object, Object>()) + " " + Collections.emptyMap().hashCode());
        t("empty iterators", () -> Collections.emptyIterator().hasNext() + " " + Collections.emptyListIterator().hasPrevious() + " " + Collections.emptyListIterator().nextIndex() + " " + Collections.emptyEnumeration().hasMoreElements());
        t("empty enumeration", () -> Collections.emptyEnumeration().nextElement());
        t("emptySorted", () -> Collections.emptySortedSet() + " " + Collections.emptySortedMap() + " " + Collections.emptyNavigableSet() + " " + Collections.emptyNavigableMap());
        t("singleton", () -> Collections.singleton(5) + " " + Collections.singletonList(null) + " " + Collections.singletonMap("k", "v") + " " + Collections.singleton(5).contains(5) + " " + Collections.singletonList(5).get(0) + " " + Collections.singleton(1).hashCode() + " " + Collections.singletonMap(1, 2).hashCode());
        t("singletonList get bad", () -> Collections.singletonList(5).get(1));
        t("singleton add", () -> Collections.singleton(5).add(1));
        t("singleton remove", () -> Collections.singleton(5).remove(5));
        t("singleton iterator", () -> { Iterator<Integer> it = Collections.singleton(5).iterator(); it.next(); return it.hasNext() + " " + it.next(); });
        t("singletonMap entries", () -> { Map<String, Integer> m = Collections.singletonMap("k", 1); return m.entrySet().iterator().next().setValue(2); });
        t("unmodifiableList", () -> { List<Integer> base = new ArrayList<>(Arrays.asList(1, 2, 3)); List<Integer> u = Collections.unmodifiableList(base); base.add(4); return u + " " + u.size() + " " + u.contains(4) + " " + u.equals(base) + " " + u.hashCode() + " " + u.subList(1, 3) + " " + u.indexOf(3); });
        t("unmodifiableList add", () -> Collections.unmodifiableList(new ArrayList<Integer>()).add(1));
        t("unmodifiableList set", () -> Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(1))).set(0, 1));
        t("unmodifiableList remove", () -> Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(1))).remove(0));
        t("unmodifiableList iterator", () -> { Iterator<Integer> it = Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(1))).iterator(); it.next(); it.remove(); return 0; });
        t("unmodifiableList listIterator set", () -> { ListIterator<Integer> it = Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(1))).listIterator(); it.next(); it.set(2); return 0; });
        t("unmodifiableList subList add", () -> Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(1, 2))).subList(0, 1).add(3));
        t("unmodifiableList sort", () -> { Collections.unmodifiableList(new ArrayList<Integer>(Arrays.asList(2, 1))).sort(null); return 0; });
        t("unmodifiableList null", () -> Collections.unmodifiableList(null));
        t("unmodifiableCollection", () -> { Collection<Integer> u = Collections.unmodifiableCollection(new HashSet<Integer>(Arrays.asList(1))); return u + " " + u.size() + " " + u.contains(1) + " " + u.equals(u) + " " + u.removeIf(x -> true); });
        t("unmodifiableSet", () -> { Set<Integer> u = Collections.unmodifiableSet(new TreeSet<Integer>(Arrays.asList(3, 1))); return u + " " + u.equals(new HashSet<Integer>(Arrays.asList(1, 3))) + " " + u.hashCode() + " " + u.add(2); });
        t("unmodifiableSortedSet", () -> { SortedSet<Integer> u = Collections.unmodifiableSortedSet(new TreeSet<Integer>(Arrays.asList(3, 1, 2))); return u.first() + " " + u.last() + " " + u.headSet(3) + " " + u.tailSet(2) + " " + u.subSet(1, 3) + " " + u.comparator() + " " + u.add(5); });
        t("unmodifiableNavigableSet", () -> { NavigableSet<Integer> u = Collections.unmodifiableNavigableSet(new TreeSet<Integer>(Arrays.asList(3, 1, 2))); return u.floor(2) + " " + u.descendingSet() + " " + u.headSet(2, true) + " " + u.pollFirst(); });
        t("unmodifiableMap", () -> { Map<String, Integer> base = new LinkedHashMap<>(); base.put("a", 1); Map<String, Integer> u = Collections.unmodifiableMap(base); base.put("b", 2); return u + " " + u.size() + " " + u.get("b") + " " + u.keySet() + u.values() + u.entrySet() + " " + u.equals(base) + " " + u.hashCode(); });
        t("unmodifiableMap put", () -> Collections.unmodifiableMap(new HashMap<String, Integer>()).put("a", 1));
        t("unmodifiableMap entry", () -> { Map<String, Integer> base = new HashMap<>(); base.put("a", 1); Collections.unmodifiableMap(base).entrySet().iterator().next().setValue(2); return 0; });
        t("unmodifiableMap keySet remove", () -> { Map<String, Integer> base = new HashMap<>(); base.put("a", 1); return Collections.unmodifiableMap(base).keySet().remove("a"); });
        t("unmodifiableMap entrySet contains", () -> { Map<String, Integer> base = new HashMap<>(); base.put("a", 1); Set<Map.Entry<String, Integer>> es = Collections.unmodifiableMap(base).entrySet(); return es.contains(new AbstractMap.SimpleEntry<String, Integer>("a", 1)) + " " + es.contains("x") + " " + es.size() + " " + es.toArray().length + " " + es.toArray(new Map.Entry[0]).length; });
        t("unmodifiableSortedMap", () -> { SortedMap<String, Integer> base = new TreeMap<>(); base.put("b", 2); base.put("a", 1); SortedMap<String, Integer> u = Collections.unmodifiableSortedMap(base); return u + " " + u.firstKey() + " " + u.lastKey() + " " + u.headMap("b") + u.tailMap("b") + u.subMap("a", "b") + " " + u.comparator(); });
        t("unmodifiableNavigableMap", () -> { NavigableMap<String, Integer> base = new TreeMap<>(); base.put("b", 2); base.put("a", 1); NavigableMap<String, Integer> u = Collections.unmodifiableNavigableMap(base); return u.floorKey("az") + " " + u.firstEntry() + " " + u.descendingMap() + " " + u.headMap("b", true) + " " + u.floorEntry("b").getValue() + " " + u.pollFirstEntry(); });
        t("synchronized", () -> { List<Integer> s = Collections.synchronizedList(new ArrayList<Integer>(Arrays.asList(3, 1, 2))); s.add(0); Collections.sort(s); Set<Integer> ss = Collections.synchronizedSet(new TreeSet<Integer>(s)); Map<Integer, Integer> sm = Collections.synchronizedMap(new TreeMap<Integer, Integer>()); sm.put(1, 2); sm.merge(1, 5, Integer::sum); Collection<Integer> sc = Collections.synchronizedCollection(new ArrayList<Integer>(s)); SortedSet<Integer> sss = Collections.synchronizedSortedSet(new TreeSet<Integer>(s)); SortedMap<Integer, Integer> ssm = Collections.synchronizedSortedMap(new TreeMap<Integer, Integer>(sm)); return s + " " + ss + " " + sm + " " + sc + " " + sss.first() + " " + sss.headSet(2) + " " + ssm.firstKey() + " " + s.subList(1, 3) + " " + s.indexOf(2) + " " + sm.keySet() + sm.values() + sm.entrySet() + " " + s.equals(sc) + s.hashCode(); });
        t("synchronized null", () -> Collections.synchronizedList(null));
        t("newSetFromMap", () -> { Set<String> s = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>()); s.add("x"); s.add("y"); s.add("x"); s.remove("y"); s.addAll(Arrays.asList("p", "q")); return s + " " + s.size() + " " + s.contains("p") + " " + s.equals(new HashSet<String>(s)) + " " + s.hashCode(); });
        t("newSetFromMap non-empty", () -> { Map<String, Boolean> m = new HashMap<>(); m.put("a", true); return Collections.newSetFromMap(m); });
        t("asLifoQueue", () -> { Queue<Integer> q = Collections.asLifoQueue(new LinkedList<Integer>()); q.add(1); q.offer(2); q.add(3); return q + " " + q.peek() + " " + q.poll() + " " + q + " " + q.element() + " " + q.remove() + " " + q.size() + q.contains(1) + q.isEmpty(); });
        t("enumeration/list", () -> { Enumeration<Integer> e = Collections.enumeration(Arrays.asList(1, 2, 3)); ArrayList<Integer> l = Collections.list(e); return l + " " + e.hasMoreElements() + " " + Collections.list(new Vector<Integer>(Arrays.asList(4, 5)).elements()); });
        t("enumeration end", () -> { Enumeration<Integer> e = Collections.enumeration(Arrays.asList(1)); e.nextElement(); return e.nextElement(); });
        t("addAll", () -> { List<Integer> l = new ArrayList<>(); return Collections.addAll(l, 1, 2, 3) + " " + Collections.addAll(l) + " " + l + " " + Collections.addAll(new HashSet<Integer>(l), 1, 2) + " " + Collections.addAll(new HashSet<Integer>(l), 1, 9); });
        t("addAll null", () -> Collections.addAll(null, 1));
        t("disjoint", () -> Collections.disjoint(Arrays.asList(1, 2), Arrays.asList(3, 4)) + " " + Collections.disjoint(Arrays.asList(1, 2), Arrays.asList(2, 4)) + " " + Collections.disjoint(new HashSet<Integer>(Arrays.asList(1)), Arrays.asList(1)) + " " + Collections.disjoint(Arrays.asList(1), new ArrayList<Integer>()) + " " + Collections.disjoint(new ArrayList<Integer>(), new ArrayList<Integer>()));
        t("reverseOrder", () -> { List<String> l = new ArrayList<>(Arrays.asList("b", "c", "a")); Collections.sort(l, Collections.reverseOrder()); Comparator<String> c = Collections.reverseOrder(String.CASE_INSENSITIVE_ORDER); return l + " " + c.compare("a", "B") + " " + Collections.reverseOrder(c).compare("a", "B") + " " + Collections.reverseOrder(null).compare("a", "b") + " " + Collections.<String>reverseOrder().reversed().compare("a", "b"); });
        t("singletonList sort", () -> { List<Integer> one = Collections.singletonList(1); one.sort(null); return one; });
        System.out.println("done");
    }
}
