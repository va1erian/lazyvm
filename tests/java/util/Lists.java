package util;

import java.util.*;
import java.util.function.*;

/** ArrayList, Arrays.asList and subList views: values, order and exception messages. */
public class Lists {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static List<Integer> list(int... xs) {
        List<Integer> l = new ArrayList<>();
        for (int x : xs) l.add(x);
        return l;
    }

    public static void main(String[] args) {
        List<Integer> a = list(5, 3, 8, 1);
        System.out.println(a + " " + a.size() + " " + a.isEmpty() + " " + a.contains(8) + " " + a.indexOf(3) + " " + a.lastIndexOf(9));
        a.add(3); a.add(0, 9); a.add(2, 7);
        System.out.println(a + " " + a.indexOf(3) + " " + a.lastIndexOf(3));
        t("get(-1)", () -> a.get(-1));
        t("get(size)", () -> a.get(a.size()));
        t("set(9)", () -> a.set(9, 1));
        t("add(9,x)", () -> { a.add(9, 1); return a; });
        t("add(-1,x)", () -> { a.add(-1, 1); return a; });
        t("remove(9)", () -> a.remove(9));
        t("remove(obj)", () -> a.remove((Object) 3) + " " + a);
        t("remove(missing)", () -> a.remove((Object) 100));
        t("set", () -> a.set(0, 10) + " " + a);
        t("addAll", () -> a.addAll(list(4, 4)) + " " + a);
        t("addAll@", () -> a.addAll(1, list(6, 6)) + " " + a);
        t("addAll@bad", () -> a.addAll(99, list(6)));
        t("addAll empty", () -> a.addAll(Collections.<Integer>emptyList()));
        t("removeAll", () -> a.removeAll(list(4, 6)) + " " + a);
        t("retainAll", () -> a.retainAll(list(10, 7, 8, 1)) + " " + a);
        t("removeAll null", () -> a.removeAll(null));
        t("containsAll", () -> a.containsAll(list(10, 8)) + " " + a.containsAll(list(10, 2)));
        a.addAll(list(2, 2, 6, 4));
        a.sort(null); System.out.println(a);
        a.sort(Collections.reverseOrder()); System.out.println(a);
        a.sort((x, y) -> (x % 3) - (y % 3)); System.out.println(a);
        a.replaceAll(x -> x * 2); System.out.println(a);
        t("replaceAll null", () -> { a.replaceAll(null); return a; });
        t("removeIf", () -> a.removeIf(x -> x % 4 == 0) + " " + a);
        t("removeIf none", () -> a.removeIf(x -> x > 1000) + " " + a);
        t("removeIf null", () -> a.removeIf(null));
        StringBuilder sb = new StringBuilder();
        a.forEach(x -> sb.append(x).append(';'));
        System.out.println(sb);
        t("forEach null", () -> { a.forEach(null); return 0; });
        t("forEach CME", () -> { int[] c = {0}; a.forEach(x -> { c[0]++; if (c[0] == 1) a.add(1); }); return c[0]; });
        t("sort CME", () -> { a.sort((x, y) -> { a.add(1); return 0; }); return a.size(); });
        t("removeIf CME", () -> a.removeIf(x -> { a.add(1); return false; }));
        t("replaceAll CME", () -> { a.replaceAll(x -> { a.add(1); return x; }); return a.size(); });
        List<Integer> b = list(1, 2, 3, 4, 5, 6, 7, 8);
        List<Integer> sub = b.subList(2, 6);
        System.out.println(sub + " " + sub.size() + " " + sub.get(0) + " " + sub.indexOf(5) + " " + sub.contains(8));
        sub.set(0, 30); sub.add(60); sub.add(0, 20); sub.remove(Integer.valueOf(4));
        System.out.println(sub + " " + b);
        sub.subList(1, 3).clear();
        System.out.println(sub + " " + b);
        t("subList bad from", () -> b.subList(-1, 2));
        t("subList bad to", () -> b.subList(0, 99));
        t("subList reversed", () -> b.subList(3, 2));
        t("sub get bad", () -> sub.get(sub.size()));
        t("sub add bad", () -> { sub.add(99, 1); return 0; });
        b.add(100);
        t("sub stale size", () -> sub.size());
        t("sub stale get", () -> sub.get(0));
        t("sub stale iterator", () -> sub.iterator().hasNext());
        t("sub sort", () -> { List<Integer> c = list(4, 2, 9, 1, 7); c.subList(1, 4).sort(null); return c; });
        t("sub removeIf", () -> { List<Integer> c = list(4, 2, 9, 1, 7); c.subList(1, 4).removeIf(x -> x > 1); return c; });
        t("sub replaceAll", () -> { List<Integer> c = list(4, 2, 9, 1, 7); c.subList(1, 4).replaceAll(x -> -x); return c; });
        t("sub addAll", () -> { List<Integer> c = list(4, 2); c.subList(1, 2).addAll(list(8, 8)); return c; });
        t("sub equals", () -> list(1, 2, 3).subList(0, 2).equals(list(1, 2)) + " " + list(1, 2, 3).subList(0, 2).hashCode());
        ArrayList<Integer> c = new ArrayList<>(list(1, 2, 3));
        c.ensureCapacity(100); c.trimToSize();
        @SuppressWarnings("unchecked") ArrayList<Integer> d = (ArrayList<Integer>) c.clone();
        d.add(4);
        System.out.println(c + " " + d + " " + c.equals(d) + " " + c.equals(list(1, 2, 3)) + " " + c.hashCode() + " " + new ArrayList<Integer>().hashCode());
        t("new ArrayList(-1)", () -> new ArrayList<Integer>(-1));
        t("new ArrayList(null)", () -> new ArrayList<Integer>(null));
        t("toArray", () -> Arrays.toString(c.toArray()) + " " + Arrays.toString(c.toArray(new Integer[0])) + " " + Arrays.toString(c.toArray(new Integer[5])));
        t("toArray bad type", () -> c.toArray(new String[0]));
        t("toArray null", () -> c.toArray((Integer[]) null));
        Integer[] big = {9, 9, 9, 9, 9};
        t("toArray reuse", () -> (c.toArray(big) == big) + " " + Arrays.toString(big));
        List<String> as = Arrays.asList("x", "y", "z");
        t("asList set", () -> as.set(1, "Y") + " " + as);
        t("asList add", () -> as.add("w"));
        t("asList remove", () -> as.remove("x"));
        t("asList removeAll", () -> as.removeAll(Arrays.asList("x")));
        t("asList removeIf", () -> as.removeIf(s -> s.equals("none")));
        t("asList clear", () -> { as.clear(); return as; });
        t("asList get bad", () -> as.get(3));
        t("asList sort", () -> { as.sort(Comparator.reverseOrder()); return as; });
        t("asList replaceAll", () -> { as.replaceAll(s -> s + s); return as; });
        t("asList iterator", () -> { Iterator<String> it = as.iterator(); it.next(); it.remove(); return as; });
        t("asList null", () -> Arrays.asList((Object[]) null));
        t("asList write-through", () -> { String[] arr = {"a", "b"}; List<String> v = Arrays.asList(arr); v.set(0, "q"); arr[1] = "r"; return v; });
        t("asList toArray type", () -> as.toArray().getClass().getSimpleName() + " " + as.toArray(new Object[0]).getClass().getSimpleName());
        t("asList indexOf", () -> as.indexOf("zz") + " " + as.contains("yy") + " " + Arrays.asList(1, null, 3).indexOf(null));
        t("asList equals", () -> as.equals(Arrays.asList("xx", "yy", "zz")) + " " + as.hashCode());
        t("ensureCapacity modCount", () -> {
            StringBuilder out = new StringBuilder();
            for (int kind = 0; kind < 4; kind++) {
                for (int n : new int[]{0, 1, 5, 10, 11, 12}) {
                    ArrayList<Integer> l = kind == 0 ? new ArrayList<Integer>() : kind == 1 ? new ArrayList<Integer>(0)
                        : kind == 2 ? new ArrayList<Integer>(list(1, 2)) : new ArrayList<Integer>(new ArrayList<Integer>());
                    if (kind == 3) l.add(1);
                    ListIterator<Integer> it = l.listIterator();
                    l.ensureCapacity(n);
                    boolean cme;
                    try { it.add(0); cme = false; } catch (ConcurrentModificationException e) { cme = true; }
                    out.append(kind).append(':').append(n).append(cme ? "!" : ".").append(' ');
                }
            }
            return out;
        });
        t("trimToSize modCount", () -> { ArrayList<Integer> l = new ArrayList<>(list(1, 2)); Iterator<Integer> it = l.iterator(); l.trimToSize(); return it.next(); });
        t("self", () -> { List<Object> s = new ArrayList<>(); s.add(s); s.add(1); return s; });
        t("nulls", () -> { List<Object> s = new ArrayList<>(); s.add(null); s.add(null); return s + " " + s.indexOf(null) + " " + s.lastIndexOf(null) + " " + s.remove(null) + " " + s; });
        t("growth", () -> { List<Integer> g = new ArrayList<>(); for (int i = 0; i < 1000; i++) g.add(i); long s = 0; for (int x : g) s += x; return g.size() + " " + s; });
        System.out.println("done");
    }
}
