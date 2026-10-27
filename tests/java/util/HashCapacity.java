package util;

import java.util.*;

/**
 * HashMap and HashSet table sizes as iteration order shows them: when the table grows for odd load
 * factors, how copies, putAll, clone and HashSet(Collection) size it, and when the compute family grows.
 */
public class HashCapacity {
    static final class K implements Comparable<K> {
        final int id, h;
        K(int id, int h) { this.id = id; this.h = h; }
        public int hashCode() { return h; }
        public boolean equals(Object o) { return o instanceof K && ((K) o).id == id; }
        public int compareTo(K o) { return Integer.compare(id, o.id); }
        public String toString() { return "K" + id; }
    }

    static Map<Integer, Integer> fill(Map<Integer, Integer> m, int n, int mul) {
        for (int i = 0; i < n; i++) m.put(i * mul, i);
        return m;
    }

    public static void main(String[] args) {
        // the threshold doubles with the table once it has 16 buckets; it is not recomputed
        float[][] cases = {{16, 0.1f, 3}, {16, 0.3f, 9}, {16, 0.6f, 19}, {32, 0.9f, 57}, {16, 1.1f, 35}, {1, 0.1f, 5}, {2, 0.6f, 7}};
        for (float[] c : cases) {
            Map<Integer, Integer> m = fill(new HashMap<Integer, Integer>((int) c[0], c[1]), (int) c[2], 17);
            System.out.println("cap " + (int) c[0] + " lf " + c[1] + " n " + (int) c[2] + ": " + m.keySet());
        }
        for (int cap : new int[]{0, 1, 3, 5, 9}) System.out.println("new HashMap(" + cap + ") with 4: " + fill(new HashMap<Integer, Integer>(cap), 4, 5).keySet());
        // copies size the table for s / loadFactor + 1 entries
        for (int n : new int[]{5, 6, 12, 13, 24, 25}) {
            Map<Integer, Integer> src = fill(new LinkedHashMap<Integer, Integer>(), n, 5);
            Map<Integer, Integer> copy = new HashMap<>(src);
            Map<Integer, Integer> put = new HashMap<>();
            put.putAll(src);
            Map<Integer, Integer> small = new HashMap<>(4, 0.5f);
            small.putAll(src);
            System.out.println("copy of " + n + ": " + copy.keySet() + " putAll " + put.keySet().equals(copy.keySet()) + " lf .5: " + small.keySet());
            System.out.println("  HashSet(Collection) of " + n + ": " + new HashSet<Integer>(src.keySet()));
            Set<Integer> hs = new HashSet<>(n, 0.75f);
            hs.addAll(src.keySet());
            System.out.println("  HashSet(" + n + ", .75) then addAll: " + hs);
        }
        // clone re-inserts into a table sized like a copy, so its order may differ from the original
        HashMap<Integer, Integer> orig = (HashMap<Integer, Integer>) fill(new HashMap<Integer, Integer>(), 12, 3);
        @SuppressWarnings("unchecked") HashMap<Integer, Integer> cl = (HashMap<Integer, Integer>) orig.clone();
        System.out.println("original " + orig.keySet() + " clone " + cl.keySet());
        HashSet<Integer> hset = new HashSet<>(orig.keySet());
        HashSet<Integer> hset2 = new HashSet<>();
        hset2.addAll(orig.keySet());
        @SuppressWarnings("unchecked") HashSet<Integer> hclone = (HashSet<Integer>) hset2.clone();
        System.out.println("HashSet added one by one " + hset2 + " its clone " + hclone + " copy " + hset);
        // putAll into a non-empty table grows once up front when the argument exceeds the threshold
        Map<K, Integer> pa = new HashMap<>();
        int[][] own = {{0, 64}, {1, 0}, {2, 64}, {3, 64}, {4, 1}};
        int[][] more = {{5, 0}, {6, 0}, {7, 64}, {8, 64}, {9, 64}, {10, 80}, {11, 16}, {12, 17}, {13, 16}, {14, 64}, {15, 48}, {16, 1}, {17, 17}};
        for (int[] k : own) pa.put(new K(k[0], k[1]), 0);
        Map<K, Integer> src = new LinkedHashMap<>();
        for (int[] k : more) src.put(new K(k[0], k[1]), 0);
        pa.putAll(src);
        System.out.println("putAll grows first: " + pa.keySet());
        // the compute family grows before it adds, and only when the size already exceeds the threshold
        Map<Integer, Integer> cf = new HashMap<>();
        for (int i = 0; i < 13; i++) cf.computeIfAbsent(i * 5, k -> 0);
        System.out.println("13 computeIfAbsent: " + cf.keySet());
        cf.computeIfAbsent(1000, k -> 0);
        System.out.println("then one more: " + cf.keySet());
        Map<Integer, Integer> pf = new HashMap<>();
        for (int i = 0; i < 13; i++) pf.put(i * 5, 0);
        System.out.println("13 put: " + pf.keySet());
        Map<Integer, Integer> mf = new HashMap<>();
        for (int i = 0; i < 13; i++) mf.merge(i * 5, 0, Integer::sum);
        mf.compute(1000, (k, v) -> null);
        System.out.println("13 merge, then a compute that adds nothing: " + mf.keySet());
        mf.putIfAbsent(5, 1);
        System.out.println("then putIfAbsent of a present key: " + mf.keySet());
        mf.put(2000, 0);
        System.out.println("then a put: " + mf.keySet());
        // a threshold that doubles to 0 is computed again from the load factor
        Map<Integer, Integer> z = new HashMap<>(16, 0.05f);
        for (int k : new int[]{0, 1, 128, 129}) z.put(k, k);
        System.out.println("doubled threshold 0: " + z.keySet());
        System.out.println("done");
    }
}
