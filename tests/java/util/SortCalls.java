package util;

import java.util.*;

/**
 * The exact sequence of compare calls made by Arrays.sort and List.sort on objects, which pins
 * the merge sort's runs, insertion sort, galloping and merge order, and where an inconsistent
 * comparator is detected. Each element logs "i?j" (original positions) when compared.
 */
public class SortCalls {
    static StringBuilder log = new StringBuilder();
    static int calls;

    static final class E implements Comparable<E> {
        final int v, i;
        E(int v, int i) { this.v = v; this.i = i; }
        public int compareTo(E o) { log.append(i).append('?').append(o.i).append(' '); calls++; return Integer.compare(v, o.v); }
    }

    static long seed = 12345;

    static int next(int bound) {
        seed = seed * 6364136223846793005L + 1442695040888963407L;
        return (int) ((seed >>> 33) % bound);
    }

    static int[] gen(int n, int pat) {
        int[] v = new int[n];
        int run = 1 + next(60), k = 2 + next(5);
        for (int i = 0; i < n; i++) {
            switch (pat) {
                case 0: v[i] = next(1000); break;
                case 1: v[i] = next(5); break;
                case 2: v[i] = (i % run) * 10 + next(3); break;
                case 3: v[i] = -(i % run) * 10 + i / run; break;
                case 4: v[i] = i + (next(20) == 0 ? next(n + 1) - n / 2 : 0); break;
                case 5: v[i] = (i * k) % (n + 1); break;
                default: v[i] = n - i + next(4); break;
            }
        }
        return v;
    }

    static E[] elems(int[] v) {
        E[] a = new E[v.length];
        for (int i = 0; i < v.length; i++) a[i] = new E(v[i], i);
        return a;
    }

    static String result(E[] a, int n) {
        StringBuilder order = new StringBuilder();
        for (E e : a) order.append(e.i).append(',');
        return "calls " + calls + " log " + log.toString().hashCode() + " order " + order.toString().hashCode() + (n <= 12 ? " | " + log.toString().trim() : "");
    }

    public static void main(String[] args) {
        int[] sizes = {2, 3, 5, 8, 12, 31, 32, 33, 64, 65, 100, 257, 1000, 4000};
        for (int n : sizes) {
            for (int pat = 0; pat < 7; pat++) {
                E[] a = elems(gen(n, pat));
                log.setLength(0); calls = 0;
                if (pat % 2 == 0) Arrays.sort(a); else Arrays.sort(a, Comparator.naturalOrder());
                System.out.println("n " + n + " pattern " + pat + ": " + result(a, n));
            }
        }
        int[] runs = {98, 96, 129, 39, 29, 48, 126};
        List<Integer> vals = new ArrayList<>();
        for (int r = 0; r < runs.length; r++) for (int j = 0; j < runs[r]; j++) vals.add((r * 7919) % 50 + 3 * j + r % 3);
        int[] rv = new int[vals.size()];
        for (int i = 0; i < rv.length; i++) rv[i] = vals.get(i);
        E[] a = elems(rv);
        log.setLength(0); calls = 0;
        Arrays.sort(a);
        System.out.println("runs " + Arrays.toString(runs) + ": " + result(a, a.length));
        a = elems(gen(300, 0));
        log.setLength(0); calls = 0;
        Arrays.sort(a, 40, 250);
        System.out.println("range 40..250 of 300: " + result(a, 300));
        a = elems(gen(10, 0));
        List<E> linked = new LinkedList<>(Arrays.asList(a));
        log.setLength(0); calls = 0;
        Collections.sort(linked);
        System.out.println("LinkedList via Collections.sort: " + result(linked.toArray(new E[0]), 10));
        List<E> arr = new ArrayList<>(Arrays.asList(a));
        log.setLength(0); calls = 0;
        arr.sort(null);
        System.out.println("ArrayList.sort: " + result(arr.toArray(new E[0]), 10));
        for (int n : new int[]{20, 40, 100, 300, 1000, 3000}) {
            StringBuilder sb = new StringBuilder("inconsistent n " + n + ":");
            for (int s = 0; s < 5; s++) {
                Integer[] b = new Integer[n];
                for (int i = 0; i < n; i++) b[i] = (i * 37) % 101;
                seed = s;
                int[] c = {0};
                try {
                    Arrays.sort(b, (x, y) -> { c[0]++; return next(3) - 1; });
                    sb.append(" ok@").append(c[0]);
                } catch (IllegalArgumentException e) {
                    sb.append(" IAE@").append(c[0]);
                }
            }
            System.out.println(sb);
        }
        try {
            E[] bad = elems(gen(500, 1));
            Arrays.sort(bad, (x, y) -> x.v < y.v ? -1 : 1);
            System.out.println("never equal: ok");
        } catch (IllegalArgumentException e) {
            System.out.println("never equal: " + e.getMessage());
        }
        // natural runs whose final merging must merge the two runs below the top first
        int[] late = {64, 32, 770, 1000, 1000, 200, 33, 200, 500, 459, 33, 1000, 1000, 500, 500, 200, 200, 64, 845, 64,
            1000, 100, 288, 33, 33, 32, 100, 33, 200, 126};
        List<Integer> lv = new ArrayList<>();
        for (int len : late) for (int j = 0; j < len; j++) lv.add(j);
        int[] lr = new int[lv.size()];
        for (int i = 0; i < lr.length; i++) lr[i] = lv.get(i);
        a = elems(lr);
        log.setLength(0); calls = 0;
        Arrays.sort(a);
        System.out.println("final merging of " + late.length + " runs: " + result(a, a.length));
        System.out.println("done");
    }
}
