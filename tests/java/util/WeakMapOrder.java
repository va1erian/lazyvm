package util;

import java.util.*;

/**
 * WeakHashMap iteration order without the collector: the bucket function, chains, growth,
 * presizing and the order of forEach (docs/design/weakhashmap.md section 2). Every key here
 * stays strongly reachable, so nothing is ever cleared.
 */
public class WeakMapOrder {
    /** A key with a chosen hash code; keys are equal when their ids are. */
    static final class K {
        final int hash;
        final int id;
        K(int hash, int id) { this.hash = hash; this.id = id; }
        public int hashCode() { return hash; }
        public boolean equals(Object o) { return o instanceof K && ((K) o).id == id; }
        public String toString() { return String.valueOf(id); }
    }

    static final List<Object> KEEP = new ArrayList<>();
    static long seed = 0x5DEECE66DL;

    /** A fixed 64-bit LCG, so the test does not depend on java.util.Random. */
    static int rnd(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    static K key(int hash, int id) {
        K k = new K(hash, id);
        KEEP.add(k);
        return k;
    }

    static String ids(Map<?, ?> m) {
        StringBuilder sb = new StringBuilder();
        for (Object k : m.keySet()) sb.append(k).append(',');
        return sb.toString();
    }

    static long mix(long h, String s) {
        for (int i = 0; i < s.length(); i++) h = h * 31 + s.charAt(i);
        return h * 1000003 + s.length();
    }

    public static void main(String[] args) {
        bucketFunction(12);
        bucketFunction(15);
        integerAndStringKeys();
        chains();
        growth();
        presize();
        callbacksOrder();
        fuzz();
        System.out.println("done");
    }

    /**
     * W1: a table of 2^k buckets filled with keys hashing 0..2^k-1 (load factor 4, so it never
     * grows), then one extra key per probe hash: it lands at the front of the bucket it shares
     * with exactly one filler, so the key after it names its bucket.
     */
    static void bucketFunction(int k) {
        int n = 1 << k;
        WeakHashMap<K, Integer> m = new WeakHashMap<>(n, 4f);
        for (int h = 0; h < n; h++) m.put(key(h, h), 0);
        long sum = 0;
        int i = 0;
        StringBuilder head = new StringBuilder();
        for (K x : m.keySet()) {
            sum = sum * 31 + x.hash;
            if (i++ < 24) head.append(x.hash).append(' ');
        }
        System.out.println("W1 fill 2^" + k + " head " + head + "sum " + sum);
        StringBuilder bits = new StringBuilder();
        for (int b = 0; b < 32; b++) bits.append(mate(m, 1 << b)).append(' ');
        System.out.println("W1 bit mates 2^" + k + " " + bits);
        StringBuilder rand = new StringBuilder();
        for (int j = 0; j < 40; j++) rand.append(mate(m, rnd(Integer.MAX_VALUE) * (j % 2 == 0 ? 1 : -1))).append(' ');
        System.out.println("W1 random mates 2^" + k + " " + rand);
    }

    /** The filler that follows a new key of hash {@code h}, that is, its bucket-mate. */
    static int mate(WeakHashMap<K, Integer> m, int h) {
        K probe = new K(h, -1);
        m.put(probe, 1);
        int found = -2;
        Iterator<K> it = m.keySet().iterator();
        while (it.hasNext()) {
            if (it.next() == probe) {
                found = it.next().hash;
                break;
            }
        }
        m.remove(probe);
        return found;
    }

    /** W1, W2, W3: everyday keys, whose order is a pure function of their hash codes. */
    static void integerAndStringKeys() {
        WeakHashMap<Integer, Integer> ints = new WeakHashMap<>();
        for (int i = 0; i < 20; i++) ints.put(i * 37 - 200, i);
        System.out.println("W1 ints " + ints);
        WeakHashMap<Integer, String> big = new WeakHashMap<>();
        for (int i = 0; i < 30; i++) big.put(i << 20 ^ i * 9973, "");
        System.out.println("W1 big " + big.keySet());
        WeakHashMap<String, Integer> s = new WeakHashMap<>();
        for (String w : "the quick brown fox jumps over the lazy dog and keeps on running far away".split(" ")) {
            s.merge(w, 1, Integer::sum);
        }
        System.out.println("W1 words " + s);
        WeakHashMap<Long, Boolean> longs = new WeakHashMap<>(64);
        for (long l = -5; l < 15; l++) longs.put(l * 0x100000001L, l % 2 == 0);
        System.out.println("W1 longs cap64 " + longs.keySet());
        System.out.println("W2 toString empty " + new WeakHashMap<>() + " one " + Collections.singletonMap(1, 2).equals(single()));
    }

    static WeakHashMap<Integer, Integer> single() {
        WeakHashMap<Integer, Integer> m = new WeakHashMap<>();
        m.put(1, 2);
        return m;
    }

    /** W3: a bucket lists its newest key first; replacing a value and removing keep the rest. */
    static void chains() {
        WeakHashMap<K, Integer> m = new WeakHashMap<>();
        for (int i = 0; i < 6; i++) m.put(key(5, i), i);
        System.out.println("W3 one bucket " + ids(m));
        m.put(key(5, 2), 99);
        m.remove(key(5, 4));
        m.put(key(5, 4), 4);
        System.out.println("W3 replace, remove, re-add " + ids(m) + " " + m.get(key(5, 2)));
    }

    /** W4, W5: the table size from each constructor, the growth point and chain reversal. */
    static void growth() {
        for (int cap : new int[]{0, 1, 2, 3, 4, 5, 8, 9, 16, 17, 100}) {
            WeakHashMap<K, Integer> m = new WeakHashMap<>(cap);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 26; i++) {
                m.put(key(i << 4 ^ (i & 3), i), i);
                if (i == 2 || i == 5 || i == 11 || i == 12 || i == 23 || i == 25) sb.append(" @").append(i + 1).append(' ').append(ids(m));
            }
            System.out.println("W4 cap " + cap + sb);
        }
        for (float lf : new float[]{0.25f, 0.5f, 0.76f, 1f, 1.5f, 3f, 100f}) {
            WeakHashMap<K, Integer> m = new WeakHashMap<>(4, lf);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                m.put(key(i << 3 | 1, i), i);
                if (i % 3 == 2) sb.append(" @").append(i + 1).append(' ').append(ids(m));
            }
            System.out.println("W4 lf " + lf + sb);
        }
        WeakHashMap<K, Integer> d = new WeakHashMap<>();
        for (int v : new int[]{1, 17, 33, 49, 65, 81}) d.put(key(v, v), 0);
        System.out.println("W5 before " + ids(d));
        for (int i = 0; d.size() < 12; i++) d.put(key(i * 64 + 2, 1000 + i), 0);
        System.out.println("W5 after growth " + ids(d));
        for (int i = 0; i < 20; i++) d.put(key(i * 64 + 3, 2000 + i), 0);
        System.out.println("W5 after second growth " + ids(d));
        for (int c : new int[]{0, 1, 2}) {
            WeakHashMap<K, Integer> w = new WeakHashMap<>(c, 100f);
            w.put(key(1, 1), 0);
            w.put(key(0, 0), 0);
            System.out.println("W4 cap " + c + " lf 100 " + ids(w));
        }
    }

    /** W6, W7: copies and putAll size the table first; a presize that leaves it too empty is undone. */
    static void presize() {
        for (int s : new int[]{0, 1, 2, 3, 6, 11, 12, 13, 23, 24, 25, 30, 47, 48}) {
            LinkedHashMap<K, Integer> src = new LinkedHashMap<>();
            for (int j = 0; j < s; j++) src.put(key(j << 12, j), j);
            WeakHashMap<K, Integer> copy = new WeakHashMap<>(src);
            WeakHashMap<K, Integer> all = new WeakHashMap<>();
            all.putAll(src);
            WeakHashMap<K, Integer> all1 = new WeakHashMap<>(1);
            all1.putAll(src);
            WeakHashMap<K, Integer> pre = new WeakHashMap<>(8);
            for (int j = 0; j < 3; j++) pre.put(key(j << 12 | 1, 500 + j), 0);
            pre.putAll(src);
            WeakHashMap<K, Integer> pre16 = new WeakHashMap<>(16);
            for (int j = 0; j < 3; j++) pre16.put(key(j << 12 | 1, 500 + j), 0);
            pre16.putAll(src);
            System.out.println("W6 s=" + s + " copy " + ids(copy) + " putAll " + ids(all) + " putAll(1) " + ids(all1)
                    + " pre8 " + ids(pre) + " pre16 " + ids(pre16));
        }
        for (float lf : new float[]{0.5f, 1f, 2f}) {
            for (int cap : new int[]{1, 2, 4, 8}) {
                StringBuilder sb = new StringBuilder();
                for (int s : new int[]{2, 3, 5, 8, 9, 17}) {
                    LinkedHashMap<K, Integer> src = new LinkedHashMap<>();
                    for (int j = 0; j < s; j++) src.put(key(j << 12, j), j);
                    WeakHashMap<K, Integer> m = new WeakHashMap<>(cap, lf);
                    for (int j = 0; j < 3; j++) m.put(key(j << 12 | 1, 500 + j), 0);
                    m.putAll(src);
                    sb.append(" s").append(s).append(' ').append(ids(m));
                }
                System.out.println("W7 cap " + cap + " lf " + lf + sb);
            }
        }
        WeakHashMap<K, Integer> again = new WeakHashMap<>(2, 0.75f);
        LinkedHashMap<K, Integer> some = new LinkedHashMap<>();
        for (int j = 0; j < 4; j++) some.put(key(j << 12, j), j);
        again.putAll(some);
        again.putAll(some);
        System.out.println("W6 putAll of present keys " + ids(again));
        again.putAll(new HashMap<K, Integer>());
        System.out.println("W6 putAll of nothing " + ids(again));
    }

    /** W8: forEach and replaceAll walk the buckets upwards; the iterators walk them downwards. */
    static void callbacksOrder() {
        WeakHashMap<K, Integer> m = new WeakHashMap<>();
        for (int i = 0; i < 12; i++) m.put(key(i % 5 + (i / 5) * 16, i), i);
        StringBuilder a = new StringBuilder();
        m.forEach((k, v) -> a.append(k).append(','));
        StringBuilder b = new StringBuilder();
        m.replaceAll((k, v) -> { b.append(k).append(','); return v + 1; });
        StringBuilder c = new StringBuilder();
        m.keySet().forEach(k -> c.append(k).append(','));
        StringBuilder d = new StringBuilder();
        m.values().forEach(v -> d.append(v).append(','));
        StringBuilder e = new StringBuilder();
        m.entrySet().iterator().forEachRemaining(x -> e.append(x).append(','));
        System.out.println("W8 forEach " + a + " replaceAll " + b + " keySet.forEach " + c + " values.forEach " + d
                + " forEachRemaining " + e);
    }

    /** W1-W7 together: random puts, removes and putAlls on colliding keys; orders and a checksum. */
    static void fuzz() {
        float[] lfs = {0.75f, 0.5f, 1f, 2f, 0.3f, 1.5f};
        long sum = 17;
        for (int run = 0; run < 800; run++) {
            int cap = rnd(40);
            float lf = lfs[rnd(lfs.length)];
            WeakHashMap<K, Integer> w = new WeakHashMap<>(cap, lf);
            List<K> pool = new ArrayList<>();
            int shift = rnd(3) == 0 ? 12 : 3;
            for (int i = 0; i < 60; i++) pool.add(key(rnd(64) << shift | rnd(4), i));
            for (int op = 0; op < 40; op++) {
                int o = rnd(10);
                if (o < 5) {
                    w.put(pool.get(rnd(60)), op);
                } else if (o < 7) {
                    w.remove(pool.get(rnd(60)));
                } else if (o < 9) {
                    LinkedHashMap<K, Integer> src = new LinkedHashMap<>();
                    int s = rnd(30);
                    for (int i = 0; i < s; i++) src.put(pool.get(rnd(60)), i);
                    w.putAll(src);
                } else if (rnd(4) == 0) {
                    w = new WeakHashMap<>(w);
                }
            }
            String order = ids(w);
            sum = mix(sum, order);
            if (run % 100 == 0) System.out.println("W1-W7 fuzz " + run + " cap " + cap + " lf " + lf + " " + order);
        }
        System.out.println("W1-W7 fuzz checksum " + sum);
    }
}
