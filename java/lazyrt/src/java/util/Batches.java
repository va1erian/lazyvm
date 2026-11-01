package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Splitting a spliterator that cannot split by itself: up to `want` elements are copied into an array and
 * handed out as a spliterator of their own. Null when nothing was left.
 */
final class Batches {
    private Batches() {
    }

    static <T> Spliterator<T> refs(Spliterator<T> from, int want, int ch) {
        Object[] a = new Object[want];
        int[] n = {0};
        Consumer<T> into = x -> a[n[0]++] = x;
        while (n[0] < want && from.tryAdvance(into)) {
            continue;
        }
        return n[0] == 0 ? null : ArraySplits.<T>refs(a, 0, n[0], ch);
    }

    static Spliterator.OfInt ints(Spliterator.OfInt from, int want, int ch) {
        int[] a = new int[want];
        int[] n = {0};
        IntConsumer into = x -> a[n[0]++] = x;
        while (n[0] < want && from.tryAdvance(into)) {
            continue;
        }
        return n[0] == 0 ? null : ArraySplits.ints(a, 0, n[0], ch);
    }

    static Spliterator.OfLong longs(Spliterator.OfLong from, int want, int ch) {
        long[] a = new long[want];
        int[] n = {0};
        LongConsumer into = x -> a[n[0]++] = x;
        while (n[0] < want && from.tryAdvance(into)) {
            continue;
        }
        return n[0] == 0 ? null : ArraySplits.longs(a, 0, n[0], ch);
    }

    static Spliterator.OfDouble doubles(Spliterator.OfDouble from, int want, int ch) {
        double[] a = new double[want];
        int[] n = {0};
        DoubleConsumer into = x -> a[n[0]++] = x;
        while (n[0] < want && from.tryAdvance(into)) {
            continue;
        }
        return n[0] == 0 ? null : ArraySplits.doubles(a, 0, n[0], ch);
    }
}
