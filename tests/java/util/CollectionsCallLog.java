package util;

import java.util.*;
import java.util.function.*;

/**
 * The exact calls (with indices) that Collections algorithms and AbstractList make on a list:
 * gN = get(N), sN = set(N), LN = listIterator(N), T = iterator(); a "t" or "S" prefix marks the
 * target or source list. Long logs print as "#count hash". Also which object's equals/compareTo runs.
 */
public class CollectionsCallLog {
    static StringBuilder log = new StringBuilder();

    static class Plain<E> extends AbstractList<E> {
        final ArrayList<E> d;
        final String p;
        Plain(String p, Collection<E> c) { this.p = p; d = new ArrayList<>(c); }
        public E get(int i) { log.append(p).append('g').append(i).append(' '); return d.get(i); }
        public E set(int i, E e) { log.append(p).append('s').append(i).append(' '); return d.set(i, e); }
        public int size() { return d.size(); }
        public ListIterator<E> listIterator(int i) { log.append(p).append('L').append(i).append(' '); return super.listIterator(i); }
        public Iterator<E> iterator() { log.append(p).append("T "); return super.iterator(); }
    }

    static class Fast<E> extends Plain<E> implements RandomAccess { Fast(String p, Collection<E> c) { super(p, c); } }

    static List<Integer> ints(int n) {
        List<Integer> b = new ArrayList<>();
        for (int i = 0; i < n; i++) b.add(i);
        return b;
    }

    static Plain<Integer> mk(boolean fast, String p, List<Integer> c) { return fast ? new Fast<>(p, c) : new Plain<>(p, c); }

    static void run(String name, int n, BiFunction<Plain<Integer>, Boolean, Object> op) {
        for (int kind = 0; kind < 2; kind++) {
            Plain<Integer> l = mk(kind == 1, "", ints(n));
            log.setLength(0);
            Object r = op.apply(l, kind == 1);
            String s = log.toString().trim();
            int count = s.isEmpty() ? 0 : s.split(" ").length;
            System.out.println(name + " " + n + (kind == 0 ? " plain" : " fast") + (r == null ? "" : " -> " + r) + ": "
                + (s.length() <= 160 ? s : "#" + count + " " + s.hashCode()) + (n <= 12 ? " " + l.d : ""));
        }
    }

    static final class Q implements Comparable<Q> {
        final int v;
        final String tag;
        Q(String tag, int v) { this.tag = tag; this.v = v; }
        public boolean equals(Object o) { log.append(tag).append(v).append(".eq(").append(o).append(") "); return o instanceof Q && ((Q) o).v == v; }
        public int hashCode() { return v; }
        public int compareTo(Q o) { log.append(tag).append(v).append(".cmp(").append(o).append(") "); return Integer.compare(v, o.v); }
        public String toString() { return tag + v; }
    }

    static List<Q> qs(int n) {
        List<Q> l = new ArrayList<>();
        for (int i = 0; i < n; i++) l.add(new Q("e", i));
        return l;
    }

    static void who(String what, Runnable r) {
        log.setLength(0);
        r.run();
        System.out.println("receiver " + what + ": " + log.toString().trim());
    }

    public static void main(String[] args) {
        for (int n : new int[]{5, 17, 18}) run("reverse", n, (l, f) -> { Collections.reverse(l); return null; });
        for (int n : new int[]{4, 5}) run("shuffle", n, (l, f) -> { Collections.shuffle(l, new Random(3)); return null; });
        run("swap", 4, (l, f) -> { Collections.swap(l, 0, 2); return null; });
        for (int n : new int[]{3, 24, 25}) run("fill", n, (l, f) -> { Collections.fill(l, 7); return null; });
        for (int d : new int[]{2, -1, 3, 0, 9}) run("rotate by " + d, 8, (l, f) -> { Collections.rotate(l, d); return null; });
        run("rotate by 2", 6, (l, f) -> { Collections.rotate(l, 2); return null; });
        for (int d : new int[]{3, -7}) for (int n : new int[]{99, 100}) run("rotate by " + d, n, (l, f) -> { Collections.rotate(l, d); return null; });
        for (int n : new int[]{4, 10, 11}) run("replaceAll 2->9", n, (l, f) -> Collections.replaceAll(l, 2, 9));
        run("replaceAll null->9", 11, (l, f) -> Collections.replaceAll(l, null, 9));
        for (int n : new int[]{9, 10}) for (int sf = 0; sf < 2; sf++) {
            final boolean srcFast = sf == 1;
            run("copy from " + (srcFast ? "fast" : "plain") + " source of", n, (l, f) -> { Collections.copy(l, mk(srcFast, "S", ints(n))); return null; });
        }
        for (int key : new int[]{3, -5, 9000}) run("binarySearch " + key, 8, (l, f) -> Collections.binarySearch(l, key));
        run("binarySearch with comparator 6", 8, (l, f) -> Collections.binarySearch(l, 6, Comparator.<Integer>naturalOrder()));
        for (int n : new int[]{4999, 5000}) for (int key : new int[]{3, 1234, -1, 9000}) run("binarySearch " + key, n, (l, f) -> Collections.binarySearch(l, key));
        for (int n : new int[]{8, 34, 35}) for (int tf = 0; tf < 2; tf++) {
            final boolean tFast = tf == 1;
            String t = tFast ? " fast target" : " plain target";
            run("indexOfSubList miss" + t, n, (l, f) -> Collections.indexOfSubList(l, mk(tFast, "t", Arrays.asList(5, 6, 8))));
            run("indexOfSubList hit" + t, n, (l, f) -> Collections.indexOfSubList(l, mk(tFast, "t", Arrays.asList(3, 4))));
            run("lastIndexOfSubList miss" + t, n, (l, f) -> Collections.lastIndexOfSubList(l, mk(tFast, "t", Arrays.asList(5, 6, 8))));
            run("lastIndexOfSubList hit" + t, n, (l, f) -> Collections.lastIndexOfSubList(l, mk(tFast, "t", Arrays.asList(3, 4))));
        }
        run("indexOfSubList empty target", 4, (l, f) -> Collections.indexOfSubList(l, mk(false, "t", ints(0))) + " " + Collections.lastIndexOfSubList(l, mk(false, "t", ints(0))));
        run("sort", 4, (l, f) -> { Collections.sort(l); return null; });
        run("max", 4, (l, f) -> Collections.max(l));
        run("min with comparator", 4, (l, f) -> Collections.min(l, Comparator.<Integer>reverseOrder()));
        run("frequency", 4, (l, f) -> Collections.frequency(l, 2));
        run("disjoint list, smaller list", 3, (l, f) -> Collections.disjoint(l, mk(false, "o", ints(2))));
        run("disjoint list, larger list", 3, (l, f) -> Collections.disjoint(l, mk(false, "o", ints(5))));
        run("disjoint set, list", 3, (l, f) -> Collections.disjoint(new TreeSet<Integer>(Arrays.asList(7)), l));
        run("equals", 3, (l, f) -> l.equals(mk(false, "o", ints(3))));
        run("hashCode", 3, (l, f) -> l.hashCode());
        run("indexOf", 3, (l, f) -> l.indexOf(2));
        run("lastIndexOf", 3, (l, f) -> l.lastIndexOf(1));
        run("contains", 3, (l, f) -> l.contains(1));
        run("toString", 3, (l, f) -> l.toString());
        run("toArray", 3, (l, f) -> l.toArray().length);
        run("subList(1, 4).get(1)", 5, (l, f) -> l.subList(1, 4).get(1));
        run("subList(1, 4) iterated", 5, (l, f) -> { for (Integer x : l.subList(1, 4)) { } return null; });
        run("subList(1, 4).listIterator(2) back", 5, (l, f) -> l.subList(1, 4).listIterator(2).previous());
        run("listIterator()", 2, (l, f) -> { l.listIterator(); return null; });
        who("binarySearch", () -> Collections.binarySearch(qs(4), new Q("k", 2)));
        who("binarySearch with comparator", () -> Collections.binarySearch(qs(4), new Q("k", 2), (x, y) -> x.compareTo(y)));
        who("replaceAll", () -> Collections.replaceAll(qs(3), new Q("o", 1), new Q("n", 9)));
        who("frequency", () -> Collections.frequency(qs(3), new Q("o", 1)));
        who("indexOfSubList", () -> Collections.indexOfSubList(qs(3), Arrays.asList(new Q("t", 1))));
        who("lastIndexOfSubList", () -> Collections.lastIndexOfSubList(qs(3), Arrays.asList(new Q("t", 1))));
        who("max", () -> Collections.max(qs(3)));
        who("min", () -> Collections.min(qs(3)));
        who("ArrayList.indexOf", () -> qs(3).indexOf(new Q("o", 1)));
        who("ArrayList.lastIndexOf", () -> qs(3).lastIndexOf(new Q("o", 1)));
        who("ArrayList.remove", () -> qs(3).remove(new Q("o", 1)));
        who("LinkedList.contains", () -> new LinkedList<>(qs(3)).contains(new Q("o", 1)));
        who("ArrayDeque.contains", () -> new ArrayDeque<>(qs(3)).contains(new Q("o", 1)));
        who("HashSet.contains", () -> new HashSet<>(qs(3)).contains(new Q("o", 1)));
        who("List.equals", () -> Arrays.asList(new Q("a", 1), new Q("a", 2)).equals(Arrays.asList(new Q("b", 1), new Q("b", 3))));
        who("Arrays.binarySearch", () -> Arrays.binarySearch(qs(4).toArray(), new Q("k", 2)));
        who("TreeMap first put", () -> new TreeMap<Q, Integer>().put(new Q("f", 1), 1));
        run("disjoint empty list, list", 0, (l, f) -> Collections.disjoint(l, mk(false, "o", ints(2))));
        run("disjoint list, empty list", 2, (l, f) -> Collections.disjoint(l, mk(false, "o", ints(0))));
        run("disjoint empty set, list", 2, (l, f) -> Collections.disjoint(new TreeSet<Integer>(), l));
        System.out.println("done");
    }
}
