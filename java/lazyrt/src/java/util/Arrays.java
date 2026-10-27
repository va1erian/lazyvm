package java.util;

import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.LongBinaryOperator;

/**
 * The per-type overloads are one-line entry points: the work is in KeySort (primitive sort and
 * search), StableSort (object sort, spec 7.S1) and AnyArray (text, hash, equality, copies).
 */
public class Arrays {
    private Arrays() {
    }

    // Spec 11: the range messages of sort, fill, binarySearch and parallelPrefix.
    static void rangeCheck(int length, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
        }
        if (from < 0) {
            throw new ArrayIndexOutOfBoundsException(from);
        }
        if (to > length) {
            throw new ArrayIndexOutOfBoundsException(to);
        }
    }

    // ---- sort and parallelSort (spec 12: same results; the object sort is spec 7.S1) ----

    public static void sort(int[] a) { KeySort.sort(a, 0, a.length); }
    public static void sort(long[] a) { KeySort.heapSort(a, 0, a.length); }
    public static void sort(short[] a) { KeySort.sort(a, 0, a.length); }
    public static void sort(char[] a) { KeySort.sort(a, 0, a.length); }
    public static void sort(byte[] a) { KeySort.sort(a, 0, a.length); }
    public static void sort(float[] a) { KeySort.sort(a, 0, a.length); }
    public static void sort(double[] a) { KeySort.sort(a, 0, a.length); }

    public static void sort(int[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }
    public static void sort(long[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.heapSort(a, from, to); }
    public static void sort(short[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }
    public static void sort(char[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }
    public static void sort(byte[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }
    public static void sort(float[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }
    public static void sort(double[] a, int from, int to) { rangeCheck(a.length, from, to); KeySort.sort(a, from, to); }

    public static void sort(Object[] a) {
        StableSort.sort(a, 0, a.length, null);
    }

    public static void sort(Object[] a, int fromIndex, int toIndex) {
        rangeCheck(a.length, fromIndex, toIndex);
        StableSort.sort(a, fromIndex, toIndex, null);
    }

    public static <T> void sort(T[] a, Comparator<? super T> c) {
        StableSort.sort(a, 0, a.length, c);
    }

    public static <T> void sort(T[] a, int fromIndex, int toIndex, Comparator<? super T> c) {
        rangeCheck(a.length, fromIndex, toIndex);
        StableSort.sort(a, fromIndex, toIndex, c);
    }

    public static void parallelSort(int[] a) { sort(a); }
    public static void parallelSort(long[] a) { sort(a); }
    public static void parallelSort(short[] a) { sort(a); }
    public static void parallelSort(char[] a) { sort(a); }
    public static void parallelSort(byte[] a) { sort(a); }
    public static void parallelSort(float[] a) { sort(a); }
    public static void parallelSort(double[] a) { sort(a); }
    public static void parallelSort(int[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(long[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(short[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(char[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(byte[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(float[] a, int from, int to) { sort(a, from, to); }
    public static void parallelSort(double[] a, int from, int to) { sort(a, from, to); }

    public static <T extends Comparable<? super T>> void parallelSort(T[] a) {
        sort(a);
    }

    public static <T extends Comparable<? super T>> void parallelSort(T[] a, int fromIndex, int toIndex) {
        sort(a, fromIndex, toIndex);
    }

    public static <T> void parallelSort(T[] a, Comparator<? super T> cmp) {
        sort(a, cmp);
    }

    public static <T> void parallelSort(T[] a, int fromIndex, int toIndex, Comparator<? super T> cmp) {
        sort(a, fromIndex, toIndex, cmp);
    }

    // ---- binarySearch ----

    public static int binarySearch(long[] a, long key) { return KeySort.search(0, a.length, i -> a[i], key); }
    public static int binarySearch(int[] a, int key) { return KeySort.search(0, a.length, i -> a[i], key); }
    public static int binarySearch(short[] a, short key) { return KeySort.search(0, a.length, i -> a[i], key); }
    public static int binarySearch(char[] a, char key) { return KeySort.search(0, a.length, i -> a[i], key); }
    public static int binarySearch(byte[] a, byte key) { return KeySort.search(0, a.length, i -> a[i], key); }

    public static int binarySearch(double[] a, double key) {
        return KeySort.search(0, a.length, i -> KeySort.key(a[i]), KeySort.key(key));
    }

    public static int binarySearch(float[] a, float key) {
        return KeySort.search(0, a.length, i -> KeySort.key(a[i]), KeySort.key(key));
    }

    public static int binarySearch(long[] a, int from, int to, long key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> a[i], key);
    }

    public static int binarySearch(int[] a, int from, int to, int key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> a[i], key);
    }

    public static int binarySearch(short[] a, int from, int to, short key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> a[i], key);
    }

    public static int binarySearch(char[] a, int from, int to, char key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> a[i], key);
    }

    public static int binarySearch(byte[] a, int from, int to, byte key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> a[i], key);
    }

    public static int binarySearch(double[] a, int from, int to, double key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> KeySort.key(a[i]), KeySort.key(key));
    }

    public static int binarySearch(float[] a, int from, int to, float key) {
        rangeCheck(a.length, from, to);
        return KeySort.search(from, to, i -> KeySort.key(a[i]), KeySort.key(key));
    }

    public static int binarySearch(Object[] a, Object key) {
        return search(a, 0, a.length, key, null);
    }

    public static int binarySearch(Object[] a, int fromIndex, int toIndex, Object key) {
        rangeCheck(a.length, fromIndex, toIndex);
        return search(a, fromIndex, toIndex, key, null);
    }

    public static <T> int binarySearch(T[] a, T key, Comparator<? super T> c) {
        return search(a, 0, a.length, key, c);
    }

    public static <T> int binarySearch(T[] a, int fromIndex, int toIndex, T key, Comparator<? super T> c) {
        rangeCheck(a.length, fromIndex, toIndex);
        return search(a, fromIndex, toIndex, key, c);
    }

    // Spec 8.C2: the probed element is the left operand, as in compare(a[mid], key).
    @SuppressWarnings("unchecked")
    private static <T> int search(T[] a, int from, int to, T key, Comparator<? super T> c) {
        Comparator<? super T> order = c != null ? c : (Comparator<? super T>) Support.NATURAL;
        int lo = from;
        int hi = to - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int r = order.compare(a[mid], key);
            if (r < 0) {
                lo = mid + 1;
            } else if (r > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    // ---- equals, hashCode, toString ----

    public static boolean equals(long[] a, long[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(int[] a, int[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(short[] a, short[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(char[] a, char[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(byte[] a, byte[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(boolean[] a, boolean[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(double[] a, double[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(float[] a, float[] a2) { return AnyArray.same(a, a2); }
    public static boolean equals(Object[] a, Object[] a2) { return AnyArray.same(a, a2); }

    public static int hashCode(long[] a) { return AnyArray.hash(a); }
    public static int hashCode(int[] a) { return AnyArray.hash(a); }
    public static int hashCode(short[] a) { return AnyArray.hash(a); }
    public static int hashCode(char[] a) { return AnyArray.hash(a); }
    public static int hashCode(byte[] a) { return AnyArray.hash(a); }
    public static int hashCode(boolean[] a) { return AnyArray.hash(a); }
    public static int hashCode(float[] a) { return AnyArray.hash(a); }
    public static int hashCode(double[] a) { return AnyArray.hash(a); }
    public static int hashCode(Object[] a) { return AnyArray.hash(a); }

    public static String toString(long[] a) { return AnyArray.text(a); }
    public static String toString(int[] a) { return AnyArray.text(a); }
    public static String toString(short[] a) { return AnyArray.text(a); }
    public static String toString(char[] a) { return AnyArray.text(a); }
    public static String toString(byte[] a) { return AnyArray.text(a); }
    public static String toString(boolean[] a) { return AnyArray.text(a); }
    public static String toString(float[] a) { return AnyArray.text(a); }
    public static String toString(double[] a) { return AnyArray.text(a); }
    public static String toString(Object[] a) { return AnyArray.text(a); }

    public static int deepHashCode(Object[] a) {
        return AnyArray.deepHash(a);
    }

    public static boolean deepEquals(Object[] a1, Object[] a2) {
        return AnyArray.deepSame(a1, a2);
    }

    public static String deepToString(Object[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        AnyArray.deepText(a, sb, new java.util.ArrayList<Object[]>());
        return sb.toString();
    }

    // ---- fill ----

    public static void fill(long[] a, long val) { fill(a, 0, a.length, val); }
    public static void fill(int[] a, int val) { fill(a, 0, a.length, val); }
    public static void fill(short[] a, short val) { fill(a, 0, a.length, val); }
    public static void fill(char[] a, char val) { fill(a, 0, a.length, val); }
    public static void fill(byte[] a, byte val) { fill(a, 0, a.length, val); }
    public static void fill(boolean[] a, boolean val) { fill(a, 0, a.length, val); }
    public static void fill(double[] a, double val) { fill(a, 0, a.length, val); }
    public static void fill(float[] a, float val) { fill(a, 0, a.length, val); }
    public static void fill(Object[] a, Object val) { fill(a, 0, a.length, val); }

    public static void fill(long[] a, int from, int to, long val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(int[] a, int from, int to, int val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(short[] a, int from, int to, short val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(char[] a, int from, int to, char val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(byte[] a, int from, int to, byte val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(boolean[] a, int from, int to, boolean val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(double[] a, int from, int to, double val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(float[] a, int from, int to, float val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    public static void fill(Object[] a, int from, int to, Object val) {
        rangeCheck(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = val;
        }
    }

    // ---- copyOf and copyOfRange ----

    public static byte[] copyOf(byte[] a, int n) { return (byte[]) AnyArray.copy(a, 0, n); }
    public static short[] copyOf(short[] a, int n) { return (short[]) AnyArray.copy(a, 0, n); }
    public static int[] copyOf(int[] a, int n) { return (int[]) AnyArray.copy(a, 0, n); }
    public static long[] copyOf(long[] a, int n) { return (long[]) AnyArray.copy(a, 0, n); }
    public static char[] copyOf(char[] a, int n) { return (char[]) AnyArray.copy(a, 0, n); }
    public static float[] copyOf(float[] a, int n) { return (float[]) AnyArray.copy(a, 0, n); }
    public static double[] copyOf(double[] a, int n) { return (double[]) AnyArray.copy(a, 0, n); }
    public static boolean[] copyOf(boolean[] a, int n) { return (boolean[]) AnyArray.copy(a, 0, n); }

    public static byte[] copyOfRange(byte[] a, int from, int to) { return (byte[]) AnyArray.range(a, from, to); }
    public static short[] copyOfRange(short[] a, int from, int to) { return (short[]) AnyArray.range(a, from, to); }
    public static int[] copyOfRange(int[] a, int from, int to) { return (int[]) AnyArray.range(a, from, to); }
    public static long[] copyOfRange(long[] a, int from, int to) { return (long[]) AnyArray.range(a, from, to); }
    public static char[] copyOfRange(char[] a, int from, int to) { return (char[]) AnyArray.range(a, from, to); }
    public static float[] copyOfRange(float[] a, int from, int to) { return (float[]) AnyArray.range(a, from, to); }
    public static double[] copyOfRange(double[] a, int from, int to) { return (double[]) AnyArray.range(a, from, to); }
    public static boolean[] copyOfRange(boolean[] a, int from, int to) { return (boolean[]) AnyArray.range(a, from, to); }

    @SuppressWarnings("unchecked")
    public static <T> T[] copyOf(T[] original, int newLength) {
        return (T[]) AnyArray.copy(original, 0, newLength);
    }

    @SuppressWarnings("unchecked")
    public static <T, U> T[] copyOf(U[] original, int newLength, Class<? extends T[]> newType) {
        T[] copy = (T[]) java.lang.reflect.Array.newInstance(newType.getComponentType(), newLength);
        System.arraycopy(original, 0, copy, 0, Math.min(original.length, newLength));
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static <T> T[] copyOfRange(T[] original, int from, int to) {
        return (T[]) AnyArray.range(original, from, to);
    }

    @SuppressWarnings("unchecked")
    public static <T, U> T[] copyOfRange(U[] original, int from, int to, Class<? extends T[]> newType) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        T[] copy = (T[]) java.lang.reflect.Array.newInstance(newType.getComponentType(), to - from);
        System.arraycopy(original, from, copy, 0, Math.min(original.length - from, to - from));
        return copy;
    }

    // ---- setAll, parallelSetAll, parallelPrefix ----

    public static <T> void setAll(T[] array, IntFunction<? extends T> generator) {
        Objects.requireNonNull(generator);
        for (int i = 0; i < array.length; i++) {
            array[i] = generator.apply(i);
        }
    }

    public static void setAll(int[] array, IntUnaryOperator generator) {
        Objects.requireNonNull(generator);
        for (int i = 0; i < array.length; i++) {
            array[i] = generator.applyAsInt(i);
        }
    }

    public static void setAll(long[] array, IntToLongFunction generator) {
        Objects.requireNonNull(generator);
        for (int i = 0; i < array.length; i++) {
            array[i] = generator.applyAsLong(i);
        }
    }

    public static void setAll(double[] array, IntToDoubleFunction generator) {
        Objects.requireNonNull(generator);
        for (int i = 0; i < array.length; i++) {
            array[i] = generator.applyAsDouble(i);
        }
    }

    public static <T> void parallelSetAll(T[] array, IntFunction<? extends T> generator) { setAll(array, generator); }
    public static void parallelSetAll(int[] array, IntUnaryOperator generator) { setAll(array, generator); }
    public static void parallelSetAll(long[] array, IntToLongFunction generator) { setAll(array, generator); }
    public static void parallelSetAll(double[] array, IntToDoubleFunction generator) { setAll(array, generator); }

    public static <T> void parallelPrefix(T[] array, BinaryOperator<T> op) { parallelPrefix(array, 0, array.length, op); }
    public static void parallelPrefix(long[] array, LongBinaryOperator op) { parallelPrefix(array, 0, array.length, op); }
    public static void parallelPrefix(double[] array, DoubleBinaryOperator op) { parallelPrefix(array, 0, array.length, op); }
    public static void parallelPrefix(int[] array, IntBinaryOperator op) { parallelPrefix(array, 0, array.length, op); }

    public static <T> void parallelPrefix(T[] array, int fromIndex, int toIndex, BinaryOperator<T> op) {
        Objects.requireNonNull(op);
        rangeCheck(array.length, fromIndex, toIndex);
        for (int i = fromIndex + 1; i < toIndex; i++) {
            array[i] = op.apply(array[i - 1], array[i]);
        }
    }

    public static void parallelPrefix(long[] array, int fromIndex, int toIndex, LongBinaryOperator op) {
        Objects.requireNonNull(op);
        rangeCheck(array.length, fromIndex, toIndex);
        for (int i = fromIndex + 1; i < toIndex; i++) {
            array[i] = op.applyAsLong(array[i - 1], array[i]);
        }
    }

    public static void parallelPrefix(double[] array, int fromIndex, int toIndex, DoubleBinaryOperator op) {
        Objects.requireNonNull(op);
        rangeCheck(array.length, fromIndex, toIndex);
        for (int i = fromIndex + 1; i < toIndex; i++) {
            array[i] = op.applyAsDouble(array[i - 1], array[i]);
        }
    }

    public static void parallelPrefix(int[] array, int fromIndex, int toIndex, IntBinaryOperator op) {
        Objects.requireNonNull(op);
        rangeCheck(array.length, fromIndex, toIndex);
        for (int i = fromIndex + 1; i < toIndex; i++) {
            array[i] = op.applyAsInt(array[i - 1], array[i]);
        }
    }

    @SafeVarargs
    public static <T> List<T> asList(T... a) {
        return new ArrayList<>(a);
    }

    /** Spec 10.X3: the fixed-size list view of an array, named java.util.Arrays$ArrayList. */
    private static class ArrayList<E> extends AbstractList<E> implements RandomAccess, java.io.Serializable {
        private final E[] a;

        ArrayList(E[] array) {
            a = Objects.requireNonNull(array);
        }

        public int size() {
            return a.length;
        }

        // Spec 10.X3: a copy with the backing array's runtime type.
        public Object[] toArray() {
            return a.clone();
        }

        public <T> T[] toArray(T[] out) {
            return Support.toArray(a, a.length, out);
        }

        public E get(int index) {
            return a[index];
        }

        public E set(int index, E element) {
            E old = a[index];
            a[index] = element;
            return old;
        }

        public int indexOf(Object o) {
            return Support.indexOf(a, 0, a.length, o);
        }

        public boolean contains(Object o) {
            return indexOf(o) >= 0;
        }

        // Spec 7.S2: sorts the backing array itself.
        public void sort(Comparator<? super E> c) {
            Arrays.sort(a, c);
        }
    }
}
