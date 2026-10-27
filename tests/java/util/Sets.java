package util;

import java.util.*;
import java.util.function.*;

/** HashSet, LinkedHashSet and TreeSet: set algebra, ordering, navigation, views and exceptions. */
public class Sets {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static <T> Set<T> set(Set<T> s, T... xs) {
        for (T x : xs) s.add(x);
        return s;
    }

    static void algebra(String name, Supplier<Set<Integer>> f) {
        Set<Integer> a = set(f.get(), 1, 2, 3, 4, 5, 6);
        Set<Integer> b = set(f.get(), 4, 5, 6, 7, 8);
        System.out.println(name + " " + a + " " + b + " " + a.size() + " " + a.contains(3) + " " + a.contains(30) + " " + a.isEmpty());
        Set<Integer> u = f.get(); u.addAll(a); System.out.println(u.addAll(b) + " union " + u + " " + u.addAll(b));
        Set<Integer> i = f.get(); i.addAll(a); System.out.println(i.retainAll(b) + " inter " + i + " " + i.retainAll(b));
        Set<Integer> d = f.get(); d.addAll(a); System.out.println(d.removeAll(b) + " diff " + d + " " + d.removeAll(b));
        System.out.println(a.containsAll(i) + " " + a.containsAll(b) + " " + a.equals(b) + " " + a.equals(new HashSet<Integer>(a)) + " " + (a.hashCode() == new HashSet<Integer>(a).hashCode()) + " " + a.hashCode());
        System.out.println(a.add(3) + " " + a.add(9) + " " + a.remove(1) + " " + a.remove(1) + " " + a);
        System.out.println(Arrays.toString(a.toArray()) + " " + Arrays.toString(a.toArray(new Integer[0])) + " " + Arrays.toString(a.toArray(new Integer[8])));
        System.out.println(a.removeIf(x -> x % 2 == 0) + " " + a + " " + a.removeIf(x -> x > 100));
        Set<Integer> c = f.get(); c.add(null == null ? 1 : 0);
        c.clear();
        System.out.println(c + " " + c.isEmpty() + " " + c.size());
        t(name + " removeAll null", () -> a.removeAll(null));
        t(name + " addAll null", () -> a.addAll(null));
        t(name + " retainAll null", () -> a.retainAll(null));
        t(name + " containsAll null", () -> a.containsAll(null));
    }

    public static void main(String[] args) {
        algebra("HashSet", HashSet::new);
        algebra("LinkedHashSet", LinkedHashSet::new);
        algebra("TreeSet", TreeSet::new);
        t("HashSet null", () -> set(new HashSet<String>(), "a", null, "b", null) + " " + new HashSet<String>(Arrays.asList(null, "x")).contains(null));
        t("LinkedHashSet null", () -> set(new LinkedHashSet<String>(), "a", null, "b", null));
        t("TreeSet null", () -> new TreeSet<String>().add(null));
        t("TreeSet null contains", () -> new TreeSet<String>(Arrays.asList("a")).contains(null));
        t("TreeSet null remove", () -> new TreeSet<String>(Arrays.asList("a")).remove(null));
        t("TreeSet mixed", () -> { TreeSet<Object> o = new TreeSet<>(); o.add("a"); o.add(1); return o; });
        t("TreeSet object", () -> new TreeSet<Object>().add(new Object()));
        t("TreeSet empty first", () -> new TreeSet<Integer>().first());
        t("TreeSet empty last", () -> new TreeSet<Integer>().last());
        t("TreeSet empty polls", () -> new TreeSet<Integer>().pollFirst() + " " + new TreeSet<Integer>().pollLast() + " " + new TreeSet<Integer>().floor(1));
        TreeSet<Integer> ts = new TreeSet<>(Arrays.asList(50, 10, 40, 20, 30, 60, 70));
        System.out.println(ts + " " + ts.first() + " " + ts.last() + " " + ts.lower(30) + " " + ts.floor(30) + " " + ts.ceiling(31) + " " + ts.higher(30) + " " + ts.lower(10) + " " + ts.higher(70));
        System.out.println(ts.headSet(30) + " " + ts.headSet(30, true) + " " + ts.tailSet(50) + " " + ts.tailSet(50, false) + " " + ts.subSet(20, 60) + " " + ts.subSet(20, false, 60, true) + " " + ts.descendingSet());
        NavigableSet<Integer> view = ts.subSet(20, true, 50, true);
        view.add(25); view.remove(30);
        System.out.println(view + " " + ts + " " + view.size() + " " + view.first() + " " + view.last() + " " + view.descendingSet() + " " + view.headSet(40));
        t("view add out of range", () -> view.add(55));
        t("view remove out of range", () -> view.remove(55) + " " + view.contains(55));
        t("view bad subSet", () -> view.subSet(10, 30));
        t("subSet reversed", () -> ts.subSet(50, 10));
        t("view pollFirst", () -> view.pollFirst() + " " + view.pollLast() + " " + ts);
        t("descending iterator", () -> { StringBuilder sb = new StringBuilder(); for (Iterator<Integer> it = ts.descendingIterator(); it.hasNext();) { int v = it.next(); sb.append(v).append(' '); if (v == 40) it.remove(); } return sb + " " + ts; });
        t("iterator remove", () -> { Iterator<Integer> it = ts.iterator(); it.next(); it.remove(); return ts; });
        t("iterator CME", () -> { for (Integer v : ts) ts.add(v + 1000); return ts; });
        t("comparator", () -> { TreeSet<String> r = new TreeSet<>(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder())); r.addAll(Arrays.asList("bb", "a", "ccc", "aa", "b", "c")); return r + " " + r.first() + " " + r.headSet("bb") + " " + r.comparator().compare("a", "b"); });
        t("copy ctors", () -> { TreeSet<Integer> r = new TreeSet<>(Collections.reverseOrder()); r.addAll(Arrays.asList(1, 2, 3)); return new TreeSet<Integer>((SortedSet<Integer>) r) + " " + new TreeSet<Integer>((Collection<Integer>) r) + " " + new TreeSet<Integer>(Arrays.asList(3, 1, 2)); });
        t("clone", () -> { @SuppressWarnings("unchecked") TreeSet<Integer> c = (TreeSet<Integer>) ts.clone(); c.add(-1); return c + " " + ts; });
        t("HashSet clone", () -> { HashSet<Integer> h = new HashSet<>(Arrays.asList(1, 2)); @SuppressWarnings("unchecked") HashSet<Integer> c = (HashSet<Integer>) h.clone(); c.add(3); return h + " " + c; });
        t("HashSet ctor bad", () -> new HashSet<Integer>(-1));
        t("HashSet ctor lf", () -> new HashSet<Integer>(4, 0f));
        t("HashSet ctor null", () -> new HashSet<Integer>(null));
        t("self hash", () -> { Set<Object> s = new HashSet<>(); s.add(1); return s + " " + s.equals(s); });
        t("equals mixed", () -> new TreeSet<Integer>(Arrays.asList(1, 2)).equals(new HashSet<Integer>(Arrays.asList(2, 1))) + " " + new HashSet<Integer>(Arrays.asList(1)).equals(Arrays.asList(1)));
        t("set of lists", () -> { Set<List<Integer>> s = new HashSet<>(); s.add(Arrays.asList(1, 2)); s.add(new ArrayList<Integer>(Arrays.asList(1, 2))); s.add(new LinkedList<Integer>(Arrays.asList(2, 1))); return s.size() + " " + s.contains(Arrays.asList(2, 1)); });
        t("strings", () -> { Set<String> s = new TreeSet<>(Arrays.asList("pear", "Apple", "fig", "apple", "Fig", "")); return s + " " + new TreeSet<String>(String.CASE_INSENSITIVE_ORDER) { { addAll(Arrays.asList("b", "B", "a")); } }; });
        System.out.println("done");
    }
}
