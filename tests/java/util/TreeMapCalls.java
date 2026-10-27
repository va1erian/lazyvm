package util;

import java.util.*;

/**
 * TreeMap's comparator calls, which reveal the shape of its red-black tree: single inserts and
 * removals, bulk building from a sorted map, and what a held entry shows after its node is reused.
 */
public class TreeMapCalls {
    static StringBuilder log = new StringBuilder();
    static final Comparator<Integer> C = (a, b) -> { log.append(a).append('?').append(b).append(' '); return Integer.compare(a, b); };
    static long seed = 7;

    static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    static String take() {
        String s = log.toString().trim();
        log.setLength(0);
        return s;
    }

    static String probe(NavigableMap<Integer, ?> m, int... keys) {
        log.setLength(0);
        for (int k : keys) m.containsKey(k);
        return take();
    }

    static String probeSet(NavigableSet<Integer> s, int... keys) {
        log.setLength(0);
        for (int k : keys) s.contains(k);
        return take();
    }

    static TreeMap<Integer, Integer> sorted(int n) {
        TreeMap<Integer, Integer> s = new TreeMap<>(C);
        for (int i = 0; i < n; i++) s.put(i * 10, i);
        log.setLength(0);
        return s;
    }

    public static void main(String[] args) {
        TreeMap<Integer, Integer> m = new TreeMap<>(C);
        StringBuilder out = new StringBuilder();
        for (int step = 1; step <= 300; step++) {
            int op = next(10), k = next(60);
            if (op < 5) m.put(k, step);
            else if (op < 7) m.remove(k);
            else if (op < 8) m.get(k);
            else for (Iterator<Integer> it = m.keySet().iterator(); it.hasNext();) if (it.next() == k) it.remove();
            if (step <= 12) System.out.println("step " + step + " op " + op + " key " + k + ": " + log.toString().trim());
            out.append(log);
            log.setLength(0);
            if (step % 50 == 0) { System.out.println("steps to " + step + ": " + out.toString().hashCode() + " " + m.keySet().hashCode()); out.setLength(0); }
        }
        for (int n : new int[]{1, 2, 3, 4, 7, 8, 10, 15}) {
            int[] all = new int[n];
            for (int i = 0; i < n; i++) all[i] = i * 10;
            TreeMap<Integer, Integer> b = new TreeMap<>(sorted(n));
            String built = take();
            String shape = probe(b, all);
            b.put(n * 10, 0);
            b.put(n * 10 + 5, 0);
            System.out.println("TreeMap(SortedMap) of " + n + " [" + built + "]: " + shape + " | then two puts: " + probe(b, n * 10 + 5));
        }
        TreeMap<Integer, Integer> pa = new TreeMap<>(C);
        pa.putAll(sorted(7));
        System.out.println("putAll(SortedMap) into empty: [" + take() + "] " + probe(pa, 0, 30, 60));
        TreeMap<Integer, Integer> other = new TreeMap<>(C.reversed());
        other.putAll(sorted(7));
        System.out.println("putAll with another comparator: " + take().split(" ").length + " calls");
        TreeMap<Integer, Integer> plain = new TreeMap<>(C);
        plain.putAll(new LinkedHashMap<>(sorted(7)));
        System.out.println("putAll(LinkedHashMap): " + take());
        TreeMap<Integer, Integer> notEmpty = new TreeMap<>(C);
        notEmpty.put(-1, 0);
        log.setLength(0);
        notEmpty.putAll(sorted(3));
        System.out.println("putAll(SortedMap) into non-empty: " + take());
        TreeSet<Integer> src = new TreeSet<>(C);
        for (int i = 0; i < 7; i++) src.add(i * 10);
        log.setLength(0);
        TreeSet<Integer> s1 = new TreeSet<>(src);
        String a1 = take();
        TreeSet<Integer> s2 = new TreeSet<>(C);
        s2.addAll(src);
        String a2 = take();
        System.out.println("TreeSet(SortedSet) [" + a1 + "] addAll into empty [" + a2 + "] shapes " + probeSet(s1, 0, 60) + " / " + probeSet(s2, 0, 60));
        log.setLength(0);
        new TreeMap<Integer, Integer>(C).put(5, 5);
        System.out.println("first put: " + take());
        TreeMap<String, Integer> nf = new TreeMap<>(Comparator.nullsFirst(Comparator.<String>naturalOrder()));
        nf.put(null, 0);
        nf.put("a", 1);
        System.out.println("null key first: " + nf);
        TreeMap<Integer, String> q = new TreeMap<>();
        for (int i = 1; i <= 7; i++) q.put(i, "v" + i);
        Map.Entry<Integer, String> e2 = null, e3 = null;
        for (Map.Entry<Integer, String> e : q.entrySet()) { if (e.getKey() == 2) e2 = e; if (e.getKey() == 3) e3 = e; }
        q.remove(2);
        System.out.println("held entries after remove(2): " + e2 + " " + e3);
        e3.setValue("X");
        e2.setValue("Y");
        System.out.println("after setValue on both: " + q);
        TreeMap<Integer, String> r = new TreeMap<>();
        for (int i = 1; i <= 7; i++) r.put(i, "v" + i);
        StringBuilder seen = new StringBuilder();
        for (Iterator<Map.Entry<Integer, String>> it = r.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, String> e = it.next();
            seen.append(e).append(' ');
            if (e.getKey() == 4 || e.getKey() == 6) it.remove();
        }
        System.out.println("iterator removal of inner nodes: " + seen + "-> " + r);
        @SuppressWarnings("unchecked") TreeMap<Integer, Integer> cc = (TreeMap<Integer, Integer>) sorted(7).clone();
        System.out.println("clone [" + take() + "]: " + probe(cc, 0, 60));
        NavigableMap<Integer, Integer> sub = sorted(10).subMap(25, true, 65, false);
        log.setLength(0);
        StringBuilder walked = new StringBuilder();
        for (int k : sub.keySet()) walked.append(k).append(' ');
        String walk = take();
        int n1 = sub.size();
        String size1 = take();
        sub.size();
        String size2 = take();
        sub.put(35, 0);
        log.setLength(0);
        sub.size();
        System.out.println("subMap walk [" + walk + "] " + walked + "| size " + n1 + " [" + size1 + "] again [" + size2 + "] after a put [" + take() + "]");
        NavigableMap<Integer, Integer> desc = sorted(10).descendingMap().headMap(45, true);
        log.setLength(0);
        System.out.println("descending view " + desc + " [" + take() + "]");
        System.out.println("done");
    }
}
