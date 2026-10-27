package java.util;

/**
 * The stable merge sort of spec 7.S1 (natural runs, binary insertion, galloping merges). Every
 * rule here is fixed by the order of compare calls the spec pins; the comments name the rule.
 */
final class StableSort<T> {
    // Spec 7.S1: arrays shorter than this are sorted by insertion alone.
    private static final int SMALL = 32;
    // Spec 7.S1: a merge first gallops after this many consecutive takes from one side.
    private static final int GALLOP_START = 7;

    private final T[] items;
    private final Comparator<? super T> order;
    private T[] spare;
    private int gallopAt = GALLOP_START;
    private int[] starts = new int[8];
    private int[] lengths = new int[8];
    private int runs;

    private StableSort(T[] items, Comparator<? super T> order) {
        this.items = items;
        this.order = order;
    }

    /** Sorts a[lo, hi) by c, or by natural order when c is null. */
    @SuppressWarnings("unchecked")
    static <T> void sort(T[] a, int lo, int hi, Comparator<? super T> c) {
        Comparator<? super T> order = c != null ? c : (Comparator<? super T>) Support.NATURAL;
        int n = hi - lo;
        if (n < 2) {
            return;
        }
        StableSort<T> s = new StableSort<T>(a, order);
        if (n < SMALL) {
            s.insert(lo, hi, lo + s.run(lo, hi));
            return;
        }
        int least = shortestRun(n);
        while (n > 0) {
            int len = s.run(lo, hi);
            if (len < least) {
                int forced = Math.min(n, least);
                s.insert(lo, lo + forced, lo + len);
                len = forced;
            }
            s.push(lo, len);
            s.collapse();
            lo += len;
            n -= len;
        }
        s.finish();
    }

    // Spec 7.S1: the minimum run length for n >= 32.
    private static int shortestRun(int n) {
        int r = 0;
        while (n >= SMALL) {
            r |= n & 1;
            n >>= 1;
        }
        return n + r;
    }

    /** The length of the natural run at lo, made ascending (a strictly descending run is reversed). */
    private int run(int lo, int hi) {
        int i = lo + 1;
        if (i == hi) {
            return 1;
        }
        if (order.compare(items[i++], items[lo]) < 0) {
            while (i < hi && order.compare(items[i], items[i - 1]) < 0) {
                i++;
            }
            reverse(lo, i);
        } else {
            while (i < hi && order.compare(items[i], items[i - 1]) >= 0) {
                i++;
            }
        }
        return i - lo;
    }

    private void reverse(int lo, int hi) {
        for (hi--; lo < hi; lo++, hi--) {
            T t = items[lo];
            items[lo] = items[hi];
            items[hi] = t;
        }
    }

    /** Binary insertion of items[sorted, hi) into the sorted prefix items[lo, sorted). */
    private void insert(int lo, int hi, int sorted) {
        for (int i = sorted; i < hi; i++) {
            T pivot = items[i];
            int left = lo;
            int right = i;
            while (left < right) {
                int mid = (left + right) >>> 1;
                if (order.compare(pivot, items[mid]) < 0) {
                    right = mid;
                } else {
                    left = mid + 1;
                }
            }
            System.arraycopy(items, left, items, left + 1, i - left);
            items[left] = pivot;
        }
    }

    private void push(int base, int len) {
        if (runs == starts.length) {
            starts = Arrays.copyOf(starts, runs * 2);
            lengths = Arrays.copyOf(lengths, runs * 2);
        }
        starts[runs] = base;
        lengths[runs++] = len;
    }

    // Spec 7.S1, collapse: only the three newest runs are looked at.
    private void collapse() {
        while (runs > 1) {
            int j = runs - 2;
            if (j >= 1 && lengths[j - 1] <= lengths[j] + lengths[j + 1]) {
                merge(lengths[j - 1] < lengths[j + 1] ? j - 1 : j);
            } else if (lengths[j] <= lengths[j + 1]) {
                merge(j);
            } else {
                return;
            }
        }
    }

    private void finish() {
        while (runs > 1) {
            int j = runs - 2;
            merge(j >= 1 && lengths[j - 1] < lengths[j + 1] ? j - 1 : j);
        }
    }

    /** Merges stacked runs i and i + 1 into one. */
    private void merge(int i) {
        int base1 = starts[i];
        int len1 = lengths[i];
        int base2 = starts[i + 1];
        int len2 = lengths[i + 1];
        lengths[i] = len1 + len2;
        if (i == runs - 3) {
            starts[i + 1] = starts[i + 2];
            lengths[i + 1] = lengths[i + 2];
        }
        runs--;
        int skip = gallop(items[base2], items, base1, len1, 0, false);
        base1 += skip;
        len1 -= skip;
        if (len1 == 0) {
            return;
        }
        len2 = gallop(items[base1 + len1 - 1], items, base2, len2, len2 - 1, true);
        if (len2 == 0) {
            return;
        }
        if (len1 <= len2) {
            StableMerge.forward(this, base1, len1, base2, len2);
        } else {
            StableMerge.backward(this, base1, len1, base2, len2);
        }
    }

    /**
     * Spec 7.S1, find-after (strict false) and find-before (strict true): how many leading elements
     * of run[base, base + len) the key belongs after, probing from `hint`.
     */
    int gallop(T key, T[] run, int base, int len, int hint, boolean strict) {
        int lo;
        int hi;
        if (after(key, run[base + hint], strict)) {
            int max = len - hint;
            int last = 0;
            int ofs = 1;
            while (ofs < max && after(key, run[base + hint + ofs], strict)) {
                last = ofs;
                ofs = grow(ofs, max);
            }
            lo = hint + last + 1;
            hi = hint + Math.min(ofs, max);
        } else {
            int max = hint + 1;
            int last = 0;
            int ofs = 1;
            while (ofs < max && !after(key, run[base + hint - ofs], strict)) {
                last = ofs;
                ofs = grow(ofs, max);
            }
            lo = hint - Math.min(ofs, max) + 1;
            hi = hint - last;
        }
        while (lo < hi) {
            int m = lo + ((hi - lo) >>> 1);
            if (after(key, run[base + m], strict)) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return hi;
    }

    /** The next probe offset, 2 * ofs + 1, kept from overflowing past `max`. */
    private static int grow(int ofs, int max) {
        int next = (ofs << 1) + 1;
        return next <= 0 ? max : next;
    }

    private boolean after(T key, T e, boolean strict) {
        int r = order.compare(key, e);
        return strict ? r > 0 : r >= 0;
    }

    boolean less(T x, T y) { return order.compare(x, y) < 0; }
    T[] array() { return items; }

    @SuppressWarnings("unchecked")
    T[] scratch(int n) {
        if (spare == null || spare.length < n) {
            spare = (T[]) new Object[n];
        }
        return spare;
    }

    int gallopAt() { return gallopAt; }
    void gallopAt(int g) { gallopAt = g; }

    static IllegalArgumentException violated() {
        return new IllegalArgumentException("Comparison method violates its general contract!");
    }
}
