package java.util;

import java.util.function.IntToLongFunction;

/**
 * Sorting and searching of primitive arrays through long keys whose signed order is the order
 * the Javadoc gives each type. Spec 12: the primitive sort algorithm is not observable.
 */
final class KeySort {
    private KeySort() {
    }

    /** A double's key: Double.compare order, so -0.0 sorts before 0.0 and NaN last. */
    static long key(double d) {
        long bits = Double.doubleToLongBits(d);
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    static double doubleOf(long key) { return Double.longBitsToDouble(key ^ ((key >> 63) & Long.MAX_VALUE)); }

    static long key(float f) {
        int bits = Float.floatToIntBits(f);
        return bits ^ ((bits >> 31) & Integer.MAX_VALUE);
    }

    static float floatOf(long key) {
        int bits = (int) key;
        return Float.intBitsToFloat(bits ^ ((bits >> 31) & Integer.MAX_VALUE));
    }

    /** The keys of a[from, to), read through `at`. */
    static long[] keys(int from, int to, IntToLongFunction at) {
        long[] k = new long[to - from];
        for (int i = 0; i < k.length; i++) {
            k[i] = at.applyAsLong(from + i);
        }
        heapSort(k, 0, k.length);
        return k;
    }

    static void sort(int[] a, int from, int to) {
        long[] k = keys(from, to, i -> a[i]);
        for (int i = 0; i < k.length; i++) {
            a[from + i] = (int) k[i];
        }
    }

    static void sort(short[] a, int from, int to) {
        long[] k = keys(from, to, i -> a[i]);
        for (int i = 0; i < k.length; i++) {
            a[from + i] = (short) k[i];
        }
    }

    static void sort(char[] a, int from, int to) {
        long[] k = keys(from, to, i -> a[i]);
        for (int i = 0; i < k.length; i++) {
            a[from + i] = (char) k[i];
        }
    }

    static void sort(byte[] a, int from, int to) {
        long[] k = keys(from, to, i -> a[i]);
        for (int i = 0; i < k.length; i++) {
            a[from + i] = (byte) k[i];
        }
    }

    static void sort(float[] a, int from, int to) {
        long[] k = keys(from, to, i -> key(a[i]));
        for (int i = 0; i < k.length; i++) {
            a[from + i] = floatOf(k[i]);
        }
    }

    static void sort(double[] a, int from, int to) {
        long[] k = keys(from, to, i -> key(a[i]));
        for (int i = 0; i < k.length; i++) {
            a[from + i] = doubleOf(k[i]);
        }
    }

    /** In-place heap sort of k[from, to). */
    static void heapSort(long[] k, int from, int to) {
        int n = to - from;
        for (int i = n / 2 - 1; i >= 0; i--) {
            siftDown(k, from, i, n);
        }
        for (int end = n - 1; end > 0; end--) {
            long top = k[from];
            k[from] = k[from + end];
            k[from + end] = top;
            siftDown(k, from, 0, end);
        }
    }

    private static void siftDown(long[] k, int base, int i, int n) {
        long v = k[base + i];
        while (2 * i + 1 < n) {
            int child = 2 * i + 1;
            if (child + 1 < n && k[base + child + 1] > k[base + child]) {
                child++;
            }
            if (k[base + child] <= v) {
                break;
            }
            k[base + i] = k[base + child];
            i = child;
        }
        k[base + i] = v;
    }

    /** Binary search of the keys at [from, to) for `key`, with the Javadoc's miss encoding. */
    static int search(int from, int to, IntToLongFunction at, long key) {
        int lo = from;
        int hi = to - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = at.applyAsLong(mid);
            if (v < key) {
                lo = mid + 1;
            } else if (v > key) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }
}
