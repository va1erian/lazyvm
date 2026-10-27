package util;

import java.util.*;

/**
 * WeakHashMap views and iterators without the collector: which hashCode and equals calls each
 * view method makes, the direction of value comparisons, what hasNext sees after a change and
 * how iterator remove behaves (docs/design/weakhashmap.md W23 to W26). Every key stays strongly
 * reachable, and the null key's position is never printed.
 */
public class WeakMapViews {
    static final StringBuilder LOG = new StringBuilder();

    /** A key or value that logs its calls; equal when the classes (c) agree. */
    static final class L {
        final int h;
        final String id;
        final int c;
        L(int h, String id, int c) { this.h = h; this.id = id; this.c = c; }
        public int hashCode() { LOG.append(id).append(".h "); return h; }
        public boolean equals(Object o) {
            LOG.append(id).append(".eq(").append(o).append(") ");
            return o instanceof L && ((L) o).c == c;
        }
        public String toString() { return id; }
    }

    static String log() {
        String s = LOG.toString();
        LOG.setLength(0);
        return s;
    }

    static void p(String label, Object result) { System.out.println(label + " " + result + " : " + log()); }
    static <A, B> Map.Entry<A, B> se(A a, B b) { return new AbstractMap.SimpleEntry<>(a, b); }

    static void t(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + " ok : " + log());
        } catch (RuntimeException e) {
            System.out.println(label + " " + e + " : " + log());
        }
    }

    public static void main(String[] args) {
        singleEntry();
        severalEntries();
        bulk();
        iteratorRemove();
        hasNextAfterChanges();
        System.out.println("done");
    }

    // S is the stored key, P an equal probe, Q an unequal probe of the same hash; SV, PV, QV
    // likewise for values.
    static final L S = new L(1, "S", 1), P = new L(1, "P", 1), Q = new L(1, "Q", 2);
    static final L SV = new L(7, "SV", 7), PV = new L(7, "PV", 7), QV = new L(7, "QV", 8);

    static WeakHashMap<L, L> one() {
        WeakHashMap<L, L> m = new WeakHashMap<>();
        m.put(S, SV);
        log();
        return m;
    }

    /** W24, W25: the calls of each view method on a map of one entry. */
    static void singleEntry() {
        WeakHashMap<L, L> m = one();
        p("W24 keySet.contains equal", m.keySet().contains(P));
        p("W24 keySet.contains same", m.keySet().contains(S));
        p("W24 keySet.remove unequal", m.keySet().remove(Q));
        p("W24 keySet.remove equal", m.keySet().remove(P));
        p("W24 keySet.remove same", one().keySet().remove(S));
        m = one();
        p("W24 entrySet.contains equal", m.entrySet().contains(se(P, PV)));
        p("W24 entrySet.contains same", m.entrySet().contains(se(S, SV)));
        p("W24 entrySet.contains other value", m.entrySet().contains(se(P, QV)));
        p("W24 entrySet.contains unequal key", m.entrySet().contains(se(Q, SV)));
        p("W24 entrySet.remove unequal key", m.entrySet().remove(se(Q, SV)));
        p("W24 entrySet.remove other value", m.entrySet().remove(se(P, QV)));
        p("W24 entrySet.remove equal", m.entrySet().remove(se(P, PV)));
        p("W24 entrySet.remove same", one().entrySet().remove(se(S, SV)));
        p("W24 entrySet.remove same key equal value", one().entrySet().remove(se(S, PV)));
        m = one();
        p("W25 containsValue equal", m.containsValue(PV));
        p("W25 containsValue same", m.containsValue(SV));
        p("W25 containsValue null", m.containsValue(null));
        p("W25 values.contains unequal", m.values().contains(QV));
        p("W25 values.remove unequal", m.values().remove(QV));
        p("W25 values.remove equal", m.values().remove(PV));
        p("W25 values.remove same", one().values().remove(SV));
        Map.Entry<L, L> e = one().entrySet().iterator().next();
        log();
        p("W24 entry equals equal", e.equals(se(P, PV)));
        p("W24 entry equals same", e.equals(se(S, SV)));
        p("W24 entry equals unequal key", e.equals(se(Q, SV)));
        p("W24 SimpleEntry equals entry", se(P, PV).equals(e));
        p("W24 entry hashCode", e.hashCode());
        p("W24 entry toString setValue", e + " " + e.setValue(PV) + " " + e.getValue());
    }

    static final L[] KS = new L[4];
    static final L[] VS = new L[4];

    /** Four entries in four buckets: the walk visits S3, S2, S1, S0 (W1, W2). */
    static WeakHashMap<L, L> four() {
        WeakHashMap<L, L> m = new WeakHashMap<>();
        for (int i = 0; i < 4; i++) {
            if (KS[i] == null) {
                KS[i] = new L(i * 100, "S" + i, 10 + i);
                VS[i] = new L(5, "V" + i, 20 + i);
            }
            m.put(KS[i], VS[i]);
        }
        log();
        return m;
    }

    static L pk(int i) { return new L(i * 100, "P" + i, 10 + i); }
    static L pv(int i) { return new L(5, "PV" + i, 20 + i); }

    /** W25: value comparisons run in iteration order, from the argument, and stop at a match. */
    static void severalEntries() {
        WeakHashMap<L, L> m = four();
        p("W25 order", m);
        p("W25 containsValue miss", m.containsValue(new L(5, "X", 99)));
        p("W25 containsValue hit", m.containsValue(pv(1)));
        p("W25 values.contains hit", m.values().contains(pv(2)));
        p("W25 values.remove hit", m.values().remove(pv(2)));
        m.put(null, new L(5, "NV", 30));
        log();
        // The null key's bucket differs between VMs, so its place in the calls is not printed.
        boolean hit = m.containsValue(pv(0));
        log();
        System.out.println("W25 containsValue hit with a null key present " + hit + " " + m.containsKey(null));
        p("W24 hashCode", four().hashCode());
    }

    /** W24: removeAll and retainAll go through the views' own contains, remove and iterator. */
    static void bulk() {
        p("W24 keySet.removeAll smaller", four().keySet().removeAll(Arrays.asList(pk(1))));
        p("W24 keySet.removeAll equal size", four().keySet().removeAll(Arrays.asList(pk(1), new L(9, "X", 0), new L(9, "Y", 0), new L(9, "Z", 0))));
        p("W24 keySet.retainAll", four().keySet().retainAll(Arrays.asList(pk(1), pk(0), pk(2), pk(3))));
        p("W24 entrySet.removeAll smaller", four().entrySet().removeAll(Arrays.asList(se(pk(1), pv(1)))));
        WeakHashMap<L, L> m = four();
        List<Map.Entry<L, L>> copy = new ArrayList<>(m.entrySet());
        p("W24 entrySet.retainAll its own entries", m.entrySet().retainAll(copy));
        p("W24 values.removeAll", four().values().removeAll(Arrays.asList(pv(1))));
        p("W24 values.retainAll", four().values().retainAll(Arrays.asList(pv(1), pv(0), pv(2), pv(3))));
        p("W24 keySet.containsAll", four().keySet().containsAll(Arrays.asList(pk(1))));
        p("W24 keySet.hashCode", four().keySet().hashCode());
        p("W24 entrySet.hashCode", four().entrySet().hashCode());
    }

    /** W26: the order of the checks, the calls removal makes, and remove at the end of a walk. */
    static void iteratorRemove() {
        WeakHashMap<L, L> m = four();
        Iterator<L> a = m.keySet().iterator();
        a.next();
        m.put(Q, QV);
        log();
        t("W26 remove after a put", a::remove);
        Iterator<L> b = m.keySet().iterator();
        m.remove(Q);
        log();
        t("W26 remove before next after a change", b::remove);
        WeakHashMap<L, L> c = new WeakHashMap<>();
        L first = new L(3, "C1", 31);
        L second = new L(3, "C2", 32);
        c.put(first, SV);
        c.put(second, SV);
        log();
        Iterator<L> ci = c.keySet().iterator();
        System.out.println("W26 chain " + ci.next() + " " + ci.next() + " : " + log());
        t("W26 remove the second of a chain", ci::remove);
        t("W26 remove twice", ci::remove);

        WeakHashMap<String, String> n = new WeakHashMap<>();
        n.put("a", "A");
        n.put("b", "B");
        n.put(null, "N");
        Iterator<String> all = n.keySet().iterator();
        while (all.hasNext()) all.next();
        all.remove();
        System.out.println("W26 remove after the walk ended removes the null key " + n);
        Iterator<String> again = n.keySet().iterator();
        while (again.hasNext()) again.next();
        again.remove();
        System.out.println("W26 and without one removes nothing " + n);
        Iterator<String> nsee = n.keySet().iterator();
        nsee.next();
        nsee.next();
        t("W26 next past the end", nsee::next);
        nsee.remove();
        System.out.println("W26 remove after NoSuchElementException " + n);
        t("W26 then remove again", nsee::remove);
        Iterator<String> live = n.keySet().iterator();
        live.next();
        live.remove();
        System.out.println("W26 remove before the end " + n);
    }

    static int bucket(int h, int n) {
        int p = h ^ (h >>> 12) ^ (h >>> 20);
        return (p ^ (p >>> 4) ^ (p >>> 7)) & (n - 1);
    }

    /** The {@code skip}-th non-negative Integer key landing in bucket b of 16 (W1). */
    static Integer in(int b, int skip) {
        for (int h = 0; ; h++) {
            if (bucket(h, 16) == b && skip-- == 0) {
                return h;
            }
        }
    }

    static WeakHashMap<Integer, Integer> at(int... buckets) {
        WeakHashMap<Integer, Integer> m = new WeakHashMap<>();
        for (int b : buckets) m.put(in(b, 0), b);
        return m;
    }

    /** W23: what hasNext reports after the map changed under a live iterator. */
    static void hasNextAfterChanges() {
        WeakHashMap<Integer, Integer> m = at();
        Iterator<Integer> it = m.keySet().iterator();
        m.put(in(15, 0), 0);
        System.out.print("W23 made on an empty map " + it.hasNext());
        m = at(3);
        it = m.keySet().iterator();
        m.remove(in(3, 0));
        m.put(in(9, 0), 0);
        System.out.println(", made on a non-empty map " + it.hasNext());
        StringBuilder sb = new StringBuilder("W23 after next at bucket 8, a key added at");
        for (int b : new int[]{12, 8, 2}) {
            m = at(8);
            it = m.keySet().iterator();
            it.next();
            m.put(in(b, b == 8 ? 1 : 0), 0);
            sb.append(' ').append(b).append(' ').append(it.hasNext());
        }
        m = at(8);
        it = m.keySet().iterator();
        it.next();
        sb.append(", after the end ").append(it.hasNext());
        m.put(in(2, 0), 0);
        System.out.println(sb.append(' ').append(it.hasNext()));
        m = new WeakHashMap<>();
        m.put(in(8, 0), 0);
        m.put(in(8, 1), 1);
        it = m.keySet().iterator();
        it.next();
        m.remove(in(8, 0));
        System.out.print("W23 chain successor removed " + it.hasNext());
        m.put(in(8, 0), 0);
        it = m.keySet().iterator();
        it.next();
        m.clear();
        System.out.print(", cleared with a successor " + it.hasNext());
        m = at(8, 4);
        it = m.keySet().iterator();
        it.next();
        m.clear();
        System.out.print(", cleared without one " + it.hasNext());
        m.put(in(2, 0), 0);
        System.out.println(", then a key added ahead " + it.hasNext());
        for (int consumed : new int[]{0, 6, 12}) {
            m = new WeakHashMap<>();
            for (int b = 15; b >= 5; b--) m.put(in(b, 0), b);
            it = m.keySet().iterator();
            for (int i = 0; i < consumed && it.hasNext(); i++) it.next();
            m.put(in(1, 0), 1);
            System.out.print((consumed == 0 ? "W23 growth after" : ",") + " " + consumed + " " + it.hasNext());
        }
        System.out.println();
    }
}
