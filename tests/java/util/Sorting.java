package util;

import java.util.*;
import java.util.function.*;

/** Arrays.sort and Collections.sort: every primitive type, ranges, stability and special values. */
public class Sorting {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    static final class P {
        final int key;
        final int seq;
        P(int key, int seq) { this.key = key; this.seq = seq; }
        public String toString() { return key + "." + seq; }
    }

    public static void main(String[] args) {
        Random r = new Random(2024);
        for (int n : new int[]{0, 1, 2, 3, 7, 8, 23, 24, 25, 100, 257, 1000}) {
            int[] ints = new int[n]; long[] longs = new long[n]; short[] shorts = new short[n]; byte[] bytes = new byte[n]; char[] chars = new char[n];
            float[] floats = new float[n]; double[] doubles = new double[n]; Integer[] boxed = new Integer[n];
            for (int i = 0; i < n; i++) {
                ints[i] = r.nextInt(50) - 25; longs[i] = r.nextLong() % 1000; shorts[i] = (short) (r.nextInt(2000) - 1000); bytes[i] = (byte) r.nextInt();
                chars[i] = (char) r.nextInt(65536); floats[i] = (r.nextInt(2000) - 1000) / 8f; doubles[i] = (r.nextInt(2000) - 1000) / 8.0; boxed[i] = r.nextInt(30);
            }
            Arrays.sort(ints); Arrays.sort(longs); Arrays.sort(shorts); Arrays.sort(bytes); Arrays.sort(chars); Arrays.sort(floats); Arrays.sort(doubles); Arrays.sort(boxed);
            boolean ok = true;
            for (int i = 1; i < n; i++) ok &= ints[i - 1] <= ints[i] && longs[i - 1] <= longs[i] && shorts[i - 1] <= shorts[i] && bytes[i - 1] <= bytes[i] && chars[i - 1] <= chars[i] && floats[i - 1] <= floats[i] && doubles[i - 1] <= doubles[i] && boxed[i - 1] <= boxed[i];
            System.out.println(n + " " + ok + " " + Arrays.hashCode(ints) + " " + Arrays.hashCode(longs) + " " + Arrays.hashCode(shorts) + " " + Arrays.hashCode(bytes) + " " + Arrays.hashCode(chars) + " " + Arrays.hashCode(floats) + " " + Arrays.hashCode(doubles) + " " + Arrays.hashCode(boxed));
        }
        double[] special = {3.5, Double.NaN, -0.0, 0.0, Double.NEGATIVE_INFINITY, -2.5, Double.POSITIVE_INFINITY, 0.0, -0.0, Double.NaN, Double.MIN_VALUE, -Double.MIN_VALUE};
        Arrays.sort(special);
        System.out.println(Arrays.toString(special));
        float[] fspecial = {1f, Float.NaN, -0f, 0f, Float.NEGATIVE_INFINITY, -1f, Float.POSITIVE_INFINITY, 0f, -0f, Float.NaN};
        Arrays.sort(fspecial);
        System.out.println(Arrays.toString(fspecial));
        int[] extremes = {Integer.MAX_VALUE, Integer.MIN_VALUE, 0, -1, 1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        Arrays.sort(extremes);
        long[] lextremes = {Long.MAX_VALUE, Long.MIN_VALUE, 0, -1, 1, Long.MIN_VALUE, Long.MAX_VALUE};
        Arrays.sort(lextremes);
        byte[] bextremes = {127, -128, 0, -1, 1, -128, 127};
        Arrays.sort(bextremes);
        char[] cextremes = {'￿', '\0', 'a', '耀', 'A'};
        Arrays.sort(cextremes);
        System.out.println(Arrays.toString(extremes) + Arrays.toString(lextremes) + Arrays.toString(bextremes) + (int) cextremes[0] + (int) cextremes[1] + (int) cextremes[2] + (int) cextremes[3] + (int) cextremes[4]);
        int[] range = {9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
        Arrays.sort(range, 2, 7);
        System.out.println(Arrays.toString(range));
        double[] drange = {9, 8, 7, 6, 5}; Arrays.sort(drange, 1, 4);
        String[] srange = {"e", "d", "c", "b", "a"}; Arrays.sort(srange, 1, 4);
        char[] crange = {'e', 'd', 'c', 'b', 'a'}; Arrays.sort(crange, 0, 3);
        System.out.println(Arrays.toString(drange) + Arrays.toString(srange) + new String(crange));
        t("range bad", () -> { Arrays.sort(range, 5, 2); return 0; });
        t("range neg", () -> { Arrays.sort(range, -1, 2); return 0; });
        t("range past end", () -> { Arrays.sort(range, 0, 11); return 0; });
        t("range bad objects", () -> { Arrays.sort(new String[3], 2, 1); return 0; });
        t("range bad comparator", () -> { Arrays.sort(new String[3], 0, 4, Comparator.<String>naturalOrder()); return 0; });
        t("null array", () -> { Arrays.sort((int[]) null); return 0; });
        P[] ps = new P[200];
        for (int i = 0; i < ps.length; i++) ps[i] = new P(r.nextInt(7), i);
        P[] copy = ps.clone();
        Arrays.sort(ps, (a, b) -> a.key - b.key);
        boolean stable = true;
        for (int i = 1; i < ps.length; i++) stable &= ps[i - 1].key < ps[i].key || (ps[i - 1].key == ps[i].key && ps[i - 1].seq < ps[i].seq);
        System.out.println("stable " + stable + " " + ps[0] + " " + ps[1] + " " + ps[100] + " " + ps[199]);
        Arrays.sort(copy, 10, 60, (a, b) -> b.key - a.key);
        StringBuilder sb = new StringBuilder();
        for (int i = 8; i < 62; i++) sb.append(copy[i]).append(' ');
        System.out.println(sb);
        List<P> pl = new ArrayList<>(Arrays.asList(copy));
        Collections.sort(pl, Comparator.comparingInt((P p) -> p.key % 3));
        sb.setLength(0);
        for (int i = 0; i < 12; i++) sb.append(pl.get(i)).append(' ');
        System.out.println(sb);
        pl.sort(Comparator.comparingInt((P p) -> p.key).reversed());
        System.out.println(pl.get(0) + " " + pl.get(199));
        String[] names = {"pear", "Apple", "fig", "apple", "Fig", "banana", "cherry", "Date", "date", "elder", "Elder", "grape", "kiwi", "Kiwi"};
        String[] n1 = names.clone(); Arrays.sort(n1);
        String[] n2 = names.clone(); Arrays.sort(n2, String.CASE_INSENSITIVE_ORDER);
        String[] n3 = names.clone(); Arrays.sort(n3, Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder()));
        String[] n4 = names.clone(); Arrays.sort(n4, Collections.reverseOrder());
        System.out.println(Arrays.toString(n1) + "\n" + Arrays.toString(n2) + "\n" + Arrays.toString(n3) + "\n" + Arrays.toString(n4));
        t("nulls in objects", () -> { Arrays.sort(new String[]{"b", null, "a"}); return 0; });
        t("nulls with comparator", () -> { String[] a = {"b", null, "a", null, "c"}; Arrays.sort(a, Comparator.nullsLast(Comparator.<String>naturalOrder())); return Arrays.toString(a); });
        t("not comparable", () -> { Arrays.sort(new Object[]{new Object(), new Object()}); return 0; });
        t("mixed types", () -> { Arrays.sort(new Object[]{1, "a", 2}); return 0; });
        t("single element not comparable", () -> { Arrays.sort(new Object[]{new Object()}); return "fine"; });
        t("empty", () -> { Arrays.sort(new String[0]); Arrays.sort(new int[0]); Arrays.sort(new String[0], null); return "fine"; });
        t("null comparator", () -> { String[] a = {"b", "a"}; Arrays.sort(a, null); return Arrays.toString(a); });
        t("comparator throws", () -> { Arrays.sort(new Integer[]{3, 2, 1}, (a, b) -> { throw new IllegalStateException("boom"); }); return 0; });
        t("parallelSort", () -> { int[] a = {3, 1, 2}; Arrays.parallelSort(a); String[] s = {"b", "a"}; Arrays.parallelSort(s); Integer[] bx = {2, 1}; Arrays.parallelSort(bx, Comparator.reverseOrder()); double[] d = {2, 1}; Arrays.parallelSort(d, 0, 2); return Arrays.toString(a) + Arrays.toString(s) + Arrays.toString(bx) + Arrays.toString(d); });
        t("sorted large strings", () -> { String[] a = new String[500]; for (int i = 0; i < a.length; i++) a[i] = Integer.toString((i * 7919) % 500, 7); Arrays.sort(a); return a[0] + " " + a[1] + " " + a[250] + " " + a[499]; });
        t("already sorted / reversed", () -> { Integer[] a = new Integer[300]; for (int i = 0; i < a.length; i++) a[i] = i; Arrays.sort(a); Integer[] b = new Integer[300]; for (int i = 0; i < b.length; i++) b[i] = 300 - i; Arrays.sort(b); int[] c = new int[3000]; for (int i = 0; i < c.length; i++) c[i] = i % 5; Arrays.sort(c); return a[299] + " " + b[0] + " " + c[0] + c[2999] + c[1500]; });
        t("comparison counts", () -> {
            StringBuilder out = new StringBuilder();
            for (int n : new int[]{2, 10, 31, 32, 33, 100, 1000}) {
                for (int pattern = 0; pattern < 4; pattern++) {
                    Integer[] a = new Integer[n];
                    Random pr = new Random(n * 10 + pattern);
                    for (int i = 0; i < n; i++) a[i] = pattern == 0 ? i : pattern == 1 ? n - i : pattern == 2 ? pr.nextInt(n) : pr.nextInt(4);
                    int[] calls = {0};
                    Arrays.sort(a, (x, y) -> { calls[0]++; return x.compareTo(y); });
                    out.append(calls[0]).append(' ');
                }
            }
            return out;
        });
        t("comparison sequence", () -> {
            Integer[] a = {5, 3, 9, 1, 7, 2, 8};
            StringBuilder out = new StringBuilder();
            Arrays.sort(a, (x, y) -> { out.append(x).append('?').append(y).append(' '); return x.compareTo(y); });
            return out + "-> " + Arrays.toString(a);
        });
        t("inconsistent comparators", () -> {
            StringBuilder out = new StringBuilder();
            for (int n : new int[]{20, 40, 100, 300}) {
                for (int seed = 0; seed < 4; seed++) {
                    Integer[] a = new Integer[n];
                    for (int i = 0; i < n; i++) a[i] = (i * 37) % 101;
                    Random cr = new Random(seed);
                    int[] calls = {0};
                    try {
                        Arrays.sort(a, (x, y) -> { calls[0]++; return cr.nextInt(3) - 1; });
                        out.append("ok").append(calls[0]);
                    } catch (IllegalArgumentException e) {
                        out.append(e.getMessage().length()).append('@').append(calls[0]);
                    }
                    out.append(' ');
                }
            }
            return out;
        });
        t("violates contract message", () -> {
            Integer[] a = new Integer[200];
            for (int i = 0; i < a.length; i++) a[i] = i % 17;
            try { Arrays.sort(a, (x, y) -> 1); return "ok"; } catch (IllegalArgumentException e) { return e.getMessage(); }
        });
        t("list.sort violates", () -> {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < 100; i++) l.add(i % 7);
            try { l.sort((x, y) -> x <= y ? -1 : 1); return "ok " + l.size(); } catch (IllegalArgumentException e) { return e.getMessage(); }
        });
        System.out.println("done");
    }
}
