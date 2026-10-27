package util;

import java.util.*;
import java.util.function.*;

/** Arrays: overloads of fill, copy, equals, hashCode, toString, binarySearch, deep operations and asList. */
public class ArraysUtil {
    static void t(String label, Supplier<Object> s) {
        try {
            System.out.println(label + ": " + s.get());
        } catch (Throwable e) {
            System.out.println(label + ": !" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }

    public static void main(String[] args) {
        boolean[] z = new boolean[4]; byte[] b = new byte[4]; char[] c = new char[4]; short[] s = new short[4];
        int[] i = new int[4]; long[] l = new long[4]; float[] f = new float[4]; double[] d = new double[4]; Object[] o = new Object[4]; String[] st = new String[4];
        Arrays.fill(z, true); Arrays.fill(b, (byte) -7); Arrays.fill(c, 'q'); Arrays.fill(s, (short) 300); Arrays.fill(i, 42); Arrays.fill(l, 1L << 40); Arrays.fill(f, 1.5f); Arrays.fill(d, -0.0); Arrays.fill(o, "o"); Arrays.fill(st, "s");
        System.out.println(Arrays.toString(z) + Arrays.toString(b) + Arrays.toString(c) + Arrays.toString(s) + Arrays.toString(i) + Arrays.toString(l) + Arrays.toString(f) + Arrays.toString(d) + Arrays.toString(o) + Arrays.toString(st));
        Arrays.fill(z, 1, 3, false); Arrays.fill(b, 0, 2, (byte) 1); Arrays.fill(c, 2, 4, 'x'); Arrays.fill(s, 1, 2, (short) -1); Arrays.fill(i, 3, 4, 0); Arrays.fill(l, 0, 1, 0L); Arrays.fill(f, 1, 2, Float.NaN); Arrays.fill(d, 2, 4, Double.POSITIVE_INFINITY); Arrays.fill(o, 0, 1, null); Arrays.fill(st, 1, 3, "t");
        System.out.println(Arrays.toString(z) + Arrays.toString(b) + Arrays.toString(c) + Arrays.toString(s) + Arrays.toString(i) + Arrays.toString(l) + Arrays.toString(f) + Arrays.toString(d) + Arrays.toString(o) + Arrays.toString(st));
        t("fill bad range", () -> { Arrays.fill(i, 3, 2, 0); return 0; });
        t("fill neg", () -> { Arrays.fill(i, -1, 2, 0); return 0; });
        t("fill past end", () -> { Arrays.fill(i, 0, 5, 0); return 0; });
        t("fill null array", () -> { Arrays.fill((int[]) null, 0); return 0; });
        t("fill ArrayStore", () -> { Arrays.fill((Object[]) st, 1); return 0; });
        System.out.println(Arrays.toString((int[]) null) + Arrays.toString(new int[0]) + Arrays.toString(new Object[]{null, "x", 1}) + Arrays.toString(new double[]{1.0, 0.1, 1e10, 1e-5, Double.NaN}) + Arrays.toString(new float[]{1f / 3, 100f}) + Arrays.toString(new char[]{'a', '\n'}).length());
        int[] src = {1, 2, 3, 4, 5};
        System.out.println(Arrays.toString(Arrays.copyOf(src, 3)) + Arrays.toString(Arrays.copyOf(src, 8)) + Arrays.toString(Arrays.copyOf(src, 0)) + Arrays.toString(Arrays.copyOfRange(src, 1, 4)) + Arrays.toString(Arrays.copyOfRange(src, 3, 8)) + Arrays.toString(Arrays.copyOfRange(src, 5, 5)));
        System.out.println(Arrays.toString(Arrays.copyOf(z, 6)) + Arrays.toString(Arrays.copyOf(b, 5)) + Arrays.toString(Arrays.copyOf(c, 5)).length() + Arrays.toString(Arrays.copyOf(s, 5)) + Arrays.toString(Arrays.copyOf(l, 5)) + Arrays.toString(Arrays.copyOf(f, 5)) + Arrays.toString(Arrays.copyOf(d, 5)) + Arrays.toString(Arrays.copyOf(st, 5)));
        System.out.println(Arrays.toString(Arrays.copyOfRange(z, 1, 6)) + Arrays.toString(Arrays.copyOfRange(b, 1, 6)) + Arrays.toString(Arrays.copyOfRange(s, 1, 6)) + Arrays.toString(Arrays.copyOfRange(l, 1, 6)) + Arrays.toString(Arrays.copyOfRange(f, 1, 6)) + Arrays.toString(Arrays.copyOfRange(d, 1, 6)) + Arrays.toString(Arrays.copyOfRange(st, 1, 6)) + Arrays.copyOfRange(c, 1, 3).length);
        t("copyOf neg", () -> Arrays.copyOf(src, -1));
        t("copyOfRange reversed", () -> Arrays.copyOfRange(src, 3, 1));
        t("copyOfRange from past end", () -> Arrays.copyOfRange(src, 6, 8));
        t("copyOfRange from neg", () -> Arrays.copyOfRange(src, -1, 3));
        t("copyOfRange objects", () -> Arrays.copyOfRange(st, -1, 3));
        t("copyOf null", () -> Arrays.copyOf((int[]) null, 1));
        t("copyOf type", () -> Arrays.copyOf(new Integer[]{1, 2}, 3, Number[].class).getClass().getSimpleName() + " " + Arrays.copyOf(new Integer[]{1, 2}, 1).getClass().getSimpleName() + " " + Arrays.copyOfRange(new Object[]{1, 2}, 0, 1, Integer[].class).getClass().getSimpleName());
        t("copyOf bad type", () -> Arrays.copyOf(new String[]{"a"}, 1, Integer[].class));
        System.out.println(Arrays.equals(src, src.clone()) + " " + Arrays.equals(src, new int[]{1, 2, 3}) + " " + Arrays.equals((int[]) null, null) + " " + Arrays.equals(src, null) + " " + Arrays.equals(new double[]{Double.NaN}, new double[]{Double.NaN}) + " " + Arrays.equals(new double[]{0.0}, new double[]{-0.0}) + " " + Arrays.equals(new float[]{Float.NaN}, new float[]{Float.NaN}) + " " + Arrays.equals(new String[]{"a", null}, new String[]{"a", null}) + " " + Arrays.equals(z, z.clone()) + Arrays.equals(b, b.clone()) + Arrays.equals(c, c.clone()) + Arrays.equals(s, s.clone()) + Arrays.equals(l, l.clone()));
        System.out.println(Arrays.hashCode(src) + " " + Arrays.hashCode((int[]) null) + " " + Arrays.hashCode(new int[0]) + " " + Arrays.hashCode(new long[]{1L << 33, -1}) + " " + Arrays.hashCode(new double[]{1.5, -0.0}) + " " + Arrays.hashCode(new float[]{1.5f}) + " " + Arrays.hashCode(new boolean[]{true, false}) + " " + Arrays.hashCode(new char[]{'a'}) + " " + Arrays.hashCode(new short[]{-3}) + " " + Arrays.hashCode(new byte[]{-3, 4}) + " " + Arrays.hashCode(new Object[]{"a", null, 7}) + " " + Objects.hash(1, "a", null));
        System.out.println(Arrays.binarySearch(src, 3) + " " + Arrays.binarySearch(src, 0) + " " + Arrays.binarySearch(src, 9) + " " + Arrays.binarySearch(src, 2, 5, 2) + " " + Arrays.binarySearch(src, 0, 0, 1) + " " + Arrays.binarySearch(new long[]{1, 5, 9}, 5L) + Arrays.binarySearch(new short[]{1, 5, 9}, (short) 6) + Arrays.binarySearch(new byte[]{1, 5, 9}, (byte) 9) + Arrays.binarySearch(new char[]{'a', 'c'}, 'b') + Arrays.binarySearch(new float[]{-1f, 0f, 1f}, 0f) + Arrays.binarySearch(new double[]{-1, 0, 1}, 0.5) + Arrays.binarySearch(new String[]{"a", "c", "e"}, "d") + Arrays.binarySearch(new Object[]{1, 3}, 3) + Arrays.binarySearch(new String[]{"c", "b", "a"}, "b", Comparator.reverseOrder()) + Arrays.binarySearch(new String[]{"c", "b", "a"}, 1, 3, "a", Comparator.<String>reverseOrder()));
        t("binarySearch signed zero/NaN", () -> Arrays.binarySearch(new double[]{-0.0, 0.0, Double.NaN}, 0.0) + " " + Arrays.binarySearch(new double[]{-0.0, 0.0, Double.NaN}, -0.0) + " " + Arrays.binarySearch(new double[]{-0.0, 0.0, Double.NaN}, Double.NaN) + " " + Arrays.binarySearch(new float[]{-0.0f, 0.0f}, 0.0f));
        t("binarySearch bad range", () -> Arrays.binarySearch(src, 3, 1, 0));
        t("binarySearch past end", () -> Arrays.binarySearch(src, 0, 6, 0));
        t("binarySearch null cmp", () -> Arrays.binarySearch(new String[]{"a", "b"}, "b", null));
        t("binarySearch not comparable", () -> Arrays.binarySearch(new Object[]{new Object(), new Object()}, new Object()));
        Object[][] deep = {{1, 2}, {"a", new int[]{3, 4}}, {null}, {}};
        System.out.println(Arrays.deepToString(deep) + " " + Arrays.deepToString(new Object[0]) + " " + Arrays.deepToString(null) + " " + Arrays.deepToString(new Object[]{new boolean[]{true}, new char[]{'c'}, new double[]{1.5}, new long[]{2}, new byte[]{3}, new short[]{4}, new float[]{5f}}));
        Object[] self = new Object[2]; self[0] = self; self[1] = new Object[]{self, 1};
        System.out.println(Arrays.deepToString(self));
        Object[] x1 = {1, new int[]{1, 2}, new Object[]{"a", new String[]{"b"}}};
        Object[] x2 = {1, new int[]{1, 2}, new Object[]{"a", new String[]{"b"}}};
        System.out.println(Arrays.deepEquals(x1, x2) + " " + Arrays.equals(x1, x2) + " " + (Arrays.deepHashCode(x1) == Arrays.deepHashCode(x2)) + " " + Arrays.deepHashCode(x1) + " " + Arrays.deepEquals(new Object[]{new int[]{1}}, new Object[]{new long[]{1}}) + " " + Arrays.deepEquals(null, null) + " " + Arrays.deepEquals(x1, null) + " " + Arrays.deepHashCode(null) + " " + Arrays.deepEquals(new Object[]{null}, new Object[]{null}));
        String[] words = {"delta", "alpha", "charlie", "bravo"};
        List<String> view = Arrays.asList(words);
        Collections.sort(view); view.set(0, "ALPHA");
        System.out.println(view + " " + Arrays.toString(words) + " " + view.size() + " " + view.contains("delta") + " " + view.getClass().getSimpleName() + " " + Arrays.asList(1, 2, 3).getClass().getName() + " " + Arrays.asList().size() + " " + Arrays.asList(new int[]{1}).size() + " " + Arrays.asList(new Integer[]{1, 2}).size());
        Integer[] boxed = {5, 3, 1};
        Arrays.setAll(boxed, k -> k * k);
        int[] gen = new int[6]; Arrays.setAll(gen, k -> k * k);
        long[] lgen = new long[3]; Arrays.setAll(lgen, k -> 1L << (40 + k));
        double[] dgen = new double[3]; Arrays.setAll(dgen, k -> k / 2.0);
        System.out.println(Arrays.toString(boxed) + Arrays.toString(gen) + Arrays.toString(lgen) + Arrays.toString(dgen));
        Arrays.parallelPrefix(gen, (p, q) -> p + q);
        long[] lp = {1, 2, 3, 4}; Arrays.parallelPrefix(lp, 1, 3, (p, q) -> p * q);
        double[] dp = {1, 2, 3}; Arrays.parallelPrefix(dp, (p, q) -> p * q);
        String[] sp = {"a", "b", "c"}; Arrays.parallelPrefix(sp, String::concat);
        System.out.println(Arrays.toString(gen) + Arrays.toString(lp) + Arrays.toString(dp) + Arrays.toString(sp));
        t("setAll null", () -> { Arrays.setAll(new int[1], null); return 0; });
        t("parallelPrefix bad range", () -> { Arrays.parallelPrefix(new int[3], 2, 1, (p, q) -> p); return 0; });
        t("parallelSetAll", () -> { int[] a = new int[4]; Arrays.parallelSetAll(a, k -> k + 1); return Arrays.toString(a); });
        System.out.println("Objects.deepEquals " + Objects.deepEquals(new int[]{1}, new int[]{1}) + " " + Objects.deepEquals(null, null) + " " + Objects.deepEquals(new Object[]{new int[]{1}}, new Object[]{new int[]{1}}) + " " + Objects.deepEquals("a", "a") + " " + Objects.deepEquals(new int[]{1}, new long[]{1}) + " " + Objects.deepEquals("a", null));
        System.out.println("done");
    }
}
