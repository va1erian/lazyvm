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
        System.out.println("done");
    }
}
