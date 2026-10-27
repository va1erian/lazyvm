package util;

import java.util.*;
import java.util.function.*;

/** Iterator and ListIterator contracts: fail-fast ConcurrentModificationException, remove, set, add. */
public class Iterators {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static List<Integer> ints(String kind) {
        List<Integer> l = kind.equals("ArrayList") ? new ArrayList<Integer>() : kind.equals("LinkedList") ? new LinkedList<Integer>() : kind.equals("Vector") ? new Vector<Integer>() : new Stack<Integer>();
        for (int i = 1; i <= 5; i++) l.add(i);
        return l;
    }

    static void lists(String kind) {
        System.out.println("== " + kind);
        List<Integer> l = ints(kind);
        t(kind + " remove during foreach (middle)", () -> { for (Integer x : l) if (x == 2) l.remove(x); return l; });
        List<Integer> l2 = ints(kind);
        t(kind + " remove during foreach (second last)", () -> { for (Integer x : l2) if (x == 4) l2.remove(x); return l2; });
        List<Integer> l3 = ints(kind);
        t(kind + " remove during foreach (last)", () -> { for (Integer x : l3) if (x == 5) l3.remove(x); return l3; });
        List<Integer> l4 = ints(kind);
        t(kind + " add during foreach", () -> { for (Integer x : l4) if (x == 1) l4.add(9); return l4; });
        List<Integer> l5 = ints(kind);
        t(kind + " set during foreach (no CME)", () -> { for (Integer x : l5) l5.set(0, x * 10); return l5; });
        List<Integer> l6 = ints(kind);
        t(kind + " iterator remove", () -> { Iterator<Integer> it = l6.iterator(); while (it.hasNext()) if (it.next() % 2 == 0) it.remove(); return l6; });
        List<Integer> l7 = ints(kind);
        t(kind + " remove without next", () -> { l7.iterator().remove(); return 0; });
        t(kind + " remove twice", () -> { Iterator<Integer> it = l7.iterator(); it.next(); it.remove(); it.remove(); return 0; });
        t(kind + " next past end", () -> { Iterator<Integer> it = ints(kind).iterator(); for (int i = 0; i < 5; i++) it.next(); return it.hasNext() + " " + it.next(); });
        t(kind + " hasNext after clear", () -> { List<Integer> c = ints(kind); Iterator<Integer> it = c.iterator(); it.next(); c.clear(); return it.hasNext(); });
        t(kind + " next after clear", () -> { List<Integer> c = ints(kind); Iterator<Integer> it = c.iterator(); it.next(); c.clear(); return it.next(); });
        t(kind + " next after external add", () -> { List<Integer> c = ints(kind); Iterator<Integer> it = c.iterator(); c.add(6); return it.next(); });
        t(kind + " forEachRemaining", () -> { Iterator<Integer> it = ints(kind).iterator(); it.next(); StringBuilder sb = new StringBuilder(); it.forEachRemaining(x -> sb.append(x)); return sb + " " + it.hasNext(); });
        t(kind + " forEachRemaining null", () -> { ints(kind).iterator().forEachRemaining(null); return 0; });
        t(kind + " forEachRemaining CME", () -> { List<Integer> c = ints(kind); c.iterator().forEachRemaining(x -> c.add(1)); return c.size(); });
        List<Integer> m = ints(kind);
        ListIterator<Integer> li = m.listIterator();
        StringBuilder sb = new StringBuilder();
        sb.append(li.next()).append(li.next()).append(li.previous()).append(li.nextIndex()).append(li.previousIndex());
        li.set(20); sb.append(m);
        li.add(25); sb.append(m).append(li.nextIndex());
        li.next(); li.remove(); sb.append(m).append(li.nextIndex());
        while (li.hasNext()) li.next();
        while (li.hasPrevious()) { int v = li.previous(); if (v % 2 == 1) li.remove(); }
        sb.append(m);
        System.out.println(sb);
        t(kind + " set after remove", () -> { ListIterator<Integer> it = ints(kind).listIterator(); it.next(); it.remove(); it.set(1); return 0; });
        t(kind + " set after add", () -> { ListIterator<Integer> it = ints(kind).listIterator(); it.next(); it.add(7); it.set(1); return 0; });
        t(kind + " add then remove", () -> { ListIterator<Integer> it = ints(kind).listIterator(); it.next(); it.add(7); it.remove(); return 0; });
        t(kind + " set before next", () -> { ints(kind).listIterator().set(1); return 0; });
        t(kind + " previous at start", () -> ints(kind).listIterator().previous());
        t(kind + " listIterator(size)", () -> { ListIterator<Integer> it = ints(kind).listIterator(5); return it.hasNext() + " " + it.previous() + " " + it.nextIndex(); });
        t(kind + " listIterator(size+1)", () -> ints(kind).listIterator(6));
        t(kind + " listIterator(-1)", () -> ints(kind).listIterator(-1));
        t(kind + " listIterator CME", () -> { List<Integer> c = ints(kind); ListIterator<Integer> it = c.listIterator(); it.next(); c.add(0); return it.previous(); });
        t(kind + " listIterator add CME", () -> { List<Integer> c = ints(kind); ListIterator<Integer> it = c.listIterator(); c.add(0); it.add(9); return c; });
        t(kind + " subList iterator CME", () -> { List<Integer> c = ints(kind); List<Integer> s = c.subList(1, 4); Iterator<Integer> it = s.iterator(); it.next(); s.add(0); return it.next(); });
        t(kind + " subList iterator remove", () -> { List<Integer> c = ints(kind); List<Integer> s = c.subList(1, 4); Iterator<Integer> it = s.iterator(); it.next(); it.remove(); return s + " " + c; });
        t(kind + " subList remove in foreach", () -> { List<Integer> c = ints(kind); for (Integer x : c.subList(0, 3)) if (x == 2) c.remove(x); return c; });
        t(kind + " subList foreach, remove sublist elem", () -> { List<Integer> c = ints(kind); List<Integer> s = c.subList(0, 3); for (Integer x : s) if (x == 2) s.remove(x); return s + " " + c; });
        t(kind + " toArray while iterating", () -> { List<Integer> c = ints(kind); Iterator<Integer> it = c.iterator(); it.next(); Object[] a = c.toArray(); it.next(); return a.length; });
    }

    public static void main(String[] args) {
        for (String kind : new String[]{"ArrayList", "LinkedList", "Vector", "Stack"}) lists(kind);
        System.out.println("== maps and sets");
        Map<Integer, Integer> hm = new HashMap<>();
        for (int i = 0; i < 6; i++) hm.put(i, i);
        t("HashMap put during keySet iteration", () -> { for (Integer k : hm.keySet()) hm.put(k + 100, 0); return hm.size(); });
        t("HashMap put existing during iteration", () -> { for (Integer k : hm.keySet()) hm.put(k, 7); return hm.get(0); });
        t("HashMap remove during iteration", () -> { for (Integer k : hm.keySet()) hm.remove(k); return hm.size(); });
        t("HashMap iterator remove", () -> { Map<Integer, Integer> c = new HashMap<>(); for (int i = 0; i < 10; i++) c.put(i, i); Iterator<Map.Entry<Integer, Integer>> it = c.entrySet().iterator(); while (it.hasNext()) if (it.next().getKey() % 2 == 0) it.remove(); return c; });
        t("HashMap iterator remove twice", () -> { Iterator<Integer> it = new HashMap<>(Collections.singletonMap(1, 1)).keySet().iterator(); it.next(); it.remove(); it.remove(); return 0; });
        t("HashMap iterator next past end", () -> { Iterator<Integer> it = new HashMap<Integer, Integer>().keySet().iterator(); return it.next(); });
        t("HashMap values iterator", () -> { Map<Integer, Integer> c = new HashMap<>(); c.put(1, 10); c.put(2, 20); Iterator<Integer> it = c.values().iterator(); it.next(); it.remove(); return c; });
        t("HashSet add during iteration", () -> { Set<Integer> s = new HashSet<>(Arrays.asList(1, 2, 3)); for (Integer x : s) s.add(x + 10); return s; });
        t("HashSet remove last during iteration", () -> { Set<Integer> s = new HashSet<>(Arrays.asList(1, 2, 3)); for (Integer x : s) if (x == 3) s.remove(x); return s; });
        t("HashSet remove first during iteration", () -> { Set<Integer> s = new HashSet<>(Arrays.asList(1, 2, 3)); for (Integer x : s) if (x == 1) s.remove(x); return s; });
        t("TreeMap put during iteration", () -> { TreeMap<Integer, Integer> c = new TreeMap<>(); c.put(1, 1); c.put(2, 2); for (Integer k : c.keySet()) c.put(k + 10, 0); return c; });
        t("TreeMap remove last during iteration", () -> { TreeMap<Integer, Integer> c = new TreeMap<>(); c.put(1, 1); c.put(2, 2); c.put(3, 3); for (Integer k : c.keySet()) if (k == 3) c.remove(k); return c; });
        t("TreeMap remove middle during iteration", () -> { TreeMap<Integer, Integer> c = new TreeMap<>(); c.put(1, 1); c.put(2, 2); c.put(3, 3); for (Integer k : c.keySet()) if (k == 2) c.remove(k); return c; });
        t("TreeMap iterator remove two children", () -> { TreeMap<Integer, Integer> c = new TreeMap<>(); for (int i = 1; i <= 10; i++) c.put(i, i); Iterator<Integer> it = c.keySet().iterator(); StringBuilder sb = new StringBuilder(); while (it.hasNext()) { int k = it.next(); sb.append(k); if (k % 2 == 1) it.remove(); } return sb + " " + c; });
        t("TreeSet iterator remove", () -> { TreeSet<Integer> c = new TreeSet<>(); for (int i = 1; i <= 10; i++) c.add(i); Iterator<Integer> it = c.descendingIterator(); while (it.hasNext()) if (it.next() % 3 != 0) it.remove(); return c; });
        t("LinkedHashMap remove during iteration", () -> { Map<Integer, Integer> c = new LinkedHashMap<>(); c.put(1, 1); c.put(2, 2); c.put(3, 3); for (Integer k : c.keySet()) if (k == 3) c.remove(k); return c; });
        t("LinkedHashMap iterator remove", () -> { Map<Integer, Integer> c = new LinkedHashMap<>(); for (int i = 0; i < 6; i++) c.put(i, i); Iterator<Integer> it = c.values().iterator(); while (it.hasNext()) if (it.next() % 2 == 1) it.remove(); return c; });
        t("Hashtable iterator", () -> { Hashtable<Integer, Integer> c = new Hashtable<>(); for (int i = 0; i < 6; i++) c.put(i, i); Iterator<Integer> it = c.keySet().iterator(); while (it.hasNext()) if (it.next() % 2 == 1) it.remove(); return c; });
        t("Hashtable CME", () -> { Hashtable<Integer, Integer> c = new Hashtable<>(); c.put(1, 1); c.put(2, 2); for (Integer k : c.keySet()) c.put(k + 10, 0); return c; });
        t("ArrayDeque iterator remove", () -> { ArrayDeque<Integer> c = new ArrayDeque<>(Arrays.asList(1, 2, 3, 4)); Iterator<Integer> it = c.iterator(); while (it.hasNext()) if (it.next() % 2 == 0) it.remove(); return c; });
        t("PriorityQueue iterator remove", () -> { PriorityQueue<Integer> c = new PriorityQueue<>(Arrays.asList(5, 1, 4, 2, 3)); Iterator<Integer> it = c.iterator(); while (it.hasNext()) if (it.next() % 2 == 0) it.remove(); return c; });
        t("Enumeration", () -> { Vector<Integer> v = new Vector<>(Arrays.asList(1, 2)); Enumeration<Integer> e = v.elements(); StringBuilder sb = new StringBuilder(); while (e.hasMoreElements()) sb.append(e.nextElement()); return sb; });
        t("Iterable foreach null", () -> { List<Integer> n = null; for (Integer x : n) { } return 0; });
        System.out.println("done");
    }
}
