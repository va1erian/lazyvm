package util;

import java.util.*;
import java.util.function.*;

/** Which overridable methods ArrayList's own methods call on a subclass (spec 8.C1): only a few do. */
public class ArrayListSubclass {
    static final StringBuilder log = new StringBuilder();

    static void t(String label, Supplier<Object> s) {
        log.setLength(0);
        String r;
        try { r = String.valueOf(s.get()); } catch (RuntimeException e) { r = "!" + e.getClass().getSimpleName(); }
        String calls = log.toString();
        System.out.println(label + " = " + r + "   calls[" + calls + "]");
    }

    /** Logs the overridable calls; the result is computed with logging off, so only the call itself shows. */
    static final class Logged<E> extends ArrayList<E> {
        boolean on = true;
        Logged(Collection<E> c) { super(c); }
        public E get(int i) { if (on) log.append("get").append(i).append(' '); return super.get(i); }
        public int size() { if (on) log.append("size "); return super.size(); }
        public boolean isEmpty() { if (on) log.append("isEmpty "); return super.isEmpty(); }
        public E set(int i, E e) { if (on) log.append("set").append(i).append(' '); return super.set(i, e); }
        public E remove(int i) { if (on) log.append("remove").append(i).append(' '); return super.remove(i); }
        public void add(int i, E e) { if (on) log.append("add").append(i).append(' '); super.add(i, e); }
        String quiet() { on = false; try { return super.toString(); } finally { on = true; } }
    }

    /** Logs the overridable methods that ArrayList's and Vector's own bulk methods might call. */
    interface Wide { }

    static final class WideList<E> extends ArrayList<E> implements Wide {
        WideList(Collection<E> c) { super(c); }
        public int indexOf(Object o) { log.append("indexOf "); return super.indexOf(o); }
        public boolean contains(Object o) { log.append("contains "); return super.contains(o); }
        public boolean removeIf(java.util.function.Predicate<? super E> p) { log.append("removeIf "); return super.removeIf(p); }
        public boolean addAll(int i, Collection<? extends E> c) { log.append("addAll@ "); return super.addAll(i, c); }
        public ListIterator<E> listIterator(int i) { log.append("listIterator(i) "); return super.listIterator(i); }
        public ListIterator<E> listIterator() { log.append("listIterator() "); return super.listIterator(); }
        public Iterator<E> iterator() { log.append("iterator "); return super.iterator(); }
        public E remove(int i) { log.append("remove@ "); return super.remove(i); }
        public int size() { log.append("size "); return super.size(); }
        public E get(int i) { log.append("get "); return super.get(i); }
    }

    static final class WideVector<E> extends Vector<E> implements Wide {
        WideVector(Collection<E> c) { super(c); }
        public int indexOf(Object o) { log.append("indexOf "); return super.indexOf(o); }
        public boolean contains(Object o) { log.append("contains "); return super.contains(o); }
        public boolean removeIf(java.util.function.Predicate<? super E> p) { log.append("removeIf "); return super.removeIf(p); }
        public boolean addAll(int i, Collection<? extends E> c) { log.append("addAll@ "); return super.addAll(i, c); }
        public ListIterator<E> listIterator(int i) { log.append("listIterator(i) "); return super.listIterator(i); }
        public ListIterator<E> listIterator() { log.append("listIterator() "); return super.listIterator(); }
        public Iterator<E> iterator() { log.append("iterator "); return super.iterator(); }
        public E remove(int i) { log.append("remove@ "); return super.remove(i); }
        public int size() { log.append("size "); return super.size(); }
        public E get(int i) { log.append("get "); return super.get(i); }
    }

    /** contains answers from `in` and throws on call number k. */
    static Collection<Object> failingOn(int k, Set<Object> in) {
        return new AbstractCollection<Object>() {
            int n;
            public boolean contains(Object o) {
                if (++n == k) {
                    throw new IllegalStateException("call " + k);
                }
                return in.contains(o);
            }
            public Iterator<Object> iterator() { return in.iterator(); }
            public int size() { return in.size(); }
        };
    }

    public static void main(String[] args) {
        Logged<Integer> l = new Logged<>(Arrays.asList(3, 1, 2));
        t("for-each", () -> { int s = 0; for (Integer x : l) s += x; return s; });
        t("listIterator steps", () -> { ListIterator<Integer> it = l.listIterator(); it.next(); it.next(); it.previous(); return it.nextIndex(); });
        t("listIterator(2)", () -> l.listIterator(2).nextIndex());
        t("forEachRemaining", () -> { StringBuilder w = new StringBuilder(); l.iterator().forEachRemaining(w::append); return w; });
        t("forEach", () -> { StringBuilder w = new StringBuilder(); l.forEach(w::append); return w; });
        t("toString", () -> l.toString().length());
        t("hashCode", () -> l.hashCode());
        t("equals", () -> l.equals(Arrays.asList(3, 1, 2)) + " " + Arrays.asList(3, 1, 2).equals(l));
        t("indexOf contains", () -> l.indexOf(2) + " " + l.contains(2) + " " + l.containsAll(Arrays.asList(1)));
        t("toArray", () -> l.toArray().length);
        t("subList", () -> l.subList(0, 2).size() + " " + l.subList(0, 2).get(1));
        t("set and remove", () -> l.set(0, 3) + " " + l.remove((Object) 9));
        t("sort", () -> { l.sort(null); return l.quiet(); });
        t("Collections.sort", () -> { Collections.sort(l, Collections.reverseOrder()); return l.quiet(); });
        t("Collections.reverse", () -> { Collections.reverse(l); return l.quiet(); });
        t("Collections.max", () -> Collections.max(l));
        t("Collections.binarySearch", () -> Collections.binarySearch(l, 2));
        t("copy and clone", () -> new ArrayList<>(l).size() + " " + ((Logged<?>) l.clone()).quiet());
        t("iterator remove", () -> { Iterator<Integer> it = l.iterator(); it.next(); it.remove(); return l.quiet(); });
        t("listIterator add and set", () -> { ListIterator<Integer> it = l.listIterator(); it.add(7); it.next(); it.set(8); return l.quiet(); });
        for (int v = 0; v < 2; v++) {
            String n = v == 0 ? "ArrayList" : "Vector";
            List<Integer> five = Arrays.asList(3, 1, 4, 1, 5);
            java.util.function.Supplier<List<Integer>> make = v == 0 ? () -> new WideList<>(five) : () -> new WideVector<>(five);
            t(n + " remove(Object)", () -> make.get().remove((Object) 4));
            t(n + " addAll", () -> make.get().addAll(Arrays.asList(7, 8)));
            t(n + " removeAll", () -> { List<Integer> w = make.get(); log.setLength(0); return w.removeAll(Arrays.asList(1)); });
            t(n + " retainAll", () -> { List<Integer> w = make.get(); log.setLength(0); return w.retainAll(Arrays.asList(1, 5)); });
            t(n + " equals", () -> { List<Integer> w = make.get(); log.setLength(0); return w.equals(Arrays.asList(3, 1, 4, 1, 5)); });
            t(n + " listIterator()", () -> { List<Integer> w = make.get(); log.setLength(0); return w.listIterator().nextIndex(); });
            StringBuilder sb = new StringBuilder();
            for (int k = 1; k <= 6; k++) {
                for (int mode = 0; mode < 2; mode++) {
                    List<Integer> b = v == 0 ? new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7)) : new Vector<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
                    Collection<Object> c = failingOn(k, new HashSet<>(Arrays.asList(1, 3, 5, 7)));
                    String r;
                    try { r = String.valueOf(mode == 0 ? b.removeAll(c) : b.retainAll(c)); } catch (IllegalStateException e) { r = e.getMessage(); }
                    sb.append(mode == 0 ? " removeAll " : " retainAll ").append(r).append(' ').append(b);
                }
            }
            System.out.println(n + " bulk removal whose contains fails:" + sb);
        }
        System.out.println("done");
    }
}
