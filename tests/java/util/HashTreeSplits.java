package util;

import java.util.*;

/** What growth does to a tree bin that iterator removals left small (spec 1.H10, H12). */
public class HashTreeSplits {
    static final class C implements Comparable<C> {
        final int id;
        C(int i) { id = i; }
        public int hashCode() { return 0; }
        public boolean equals(Object o) { return o instanceof C && ((C) o).id == id; }
        public int compareTo(C o) { return Integer.compare(id, o.id); }
        public String toString() { return "C" + id; }
    }

    /** The keys of bin 0 in iteration order: the C keys, which come first. */
    static String bin(HashMap<Object, Object> m) {
        StringBuilder b = new StringBuilder();
        for (Object k : m.keySet()) if (k instanceof C) b.append(k).append(' ');
        return b.toString().trim();
    }

    public static void main(String[] args) {
        for (int grows : new int[]{1, 2, 3}) {
            for (int keep = 1; keep <= 3; keep++) {
                for (int op = 0; op < 3; op++) {
                    HashMap<Object, Object> m = new HashMap<>(64);
                    for (int i = 0; i < 12; i++) m.put(new C(i * 3), i);
                    Iterator<Object> it = m.keySet().iterator();
                    for (int n = 12 - keep; n > 0; n--) {
                        it.next();
                        it.remove();
                    }
                    Map.Entry<Object, Object> held = m.entrySet().iterator().next();
                    // Odd keys never land in bin 0, so the bin keeps only what removal left.
                    int puts = grows == 1 ? 60 : grows == 2 ? 100 : 250;
                    for (int g = 0; g < puts; g++) m.put(2 * g + 1, 0);
                    held.setValue("written");
                    C k = new C(3000);
                    if (op == 0) m.compute(k, (x, v) -> 1); else if (op == 1) m.put(k, 1); else m.computeIfAbsent(k, x -> 1);
                    System.out.println("grown " + grows + "x, kept " + keep + ", " + (op == 0 ? "compute" : op == 1 ? "put" : "computeIfAbsent")
                        + ": held entry wrote through " + "written".equals(m.get(held.getKey())) + ", bin " + bin(m));
                }
            }
        }
        System.out.println("done");
    }
}
