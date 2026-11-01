package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** Spliterators over a window of an array; `ch` is reported exactly as given. */
final class ArraySplits {
    private ArraySplits() {
    }

    /** Checks `from <= to <= length` the way the array factories report it (spec R5). */
    static void check(int length, int from, int to) {
        if (from > to) {
            throw new ArrayIndexOutOfBoundsException("origin(" + from + ") > fence(" + to + ")");
        }
        if (from < 0) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + from);
        }
        if (to > length) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + to);
        }
    }

    static final int SIZES = Spliterator.SIZED | Spliterator.SUBSIZED;

    /** The range-checked spliterators of the array factories; `ch` is what the caller adds to SIZED|SUBSIZED. */
    static <T> Spliterator<T> refs(Object[] a, int from, int to, int ch) {
        check(a.length, from, to);
        return new Refs<T>(a, from, to, ch | SIZES);
    }

    static Spliterator.OfInt ints(int[] a, int from, int to, int ch) {
        check(a.length, from, to);
        return new Ints(a, from, to, ch | SIZES);
    }

    static Spliterator.OfLong longs(long[] a, int from, int to, int ch) {
        check(a.length, from, to);
        return new Longs(a, from, to, ch | SIZES);
    }

    static Spliterator.OfDouble doubles(double[] a, int from, int to, int ch) {
        check(a.length, from, to);
        return new Doubles(a, from, to, ch | SIZES);
    }

    static final class Refs<T> implements Spliterator<T> {
        private final Object[] a;
        private int i;
        private final int hi;
        private final int ch;

        Refs(Object[] a, int from, int to, int ch) {
            this.a = a;
            this.i = from;
            this.hi = to;
            this.ch = ch;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean tryAdvance(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            if (i < hi) {
                action.accept((T) a[i++]);
                return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            Object[] arr = a;
            int end = hi;
            int from = i;
            i = end;
            for (int k = from; k < end; k++) {
                action.accept((T) arr[k]);
            }
        }

        @Override
        public Spliterator<T> trySplit() {
            int lo = i;
            int mid = (lo + hi) >>> 1;
            if (lo >= mid) {
                return null;
            }
            i = mid;
            return new Refs<>(a, lo, mid, ch);
        }

        @Override
        public long estimateSize() { return hi - i; }

        @Override
        public int characteristics() { return ch; }

        @Override
        @SuppressWarnings("unchecked")
        public Comparator<? super T> getComparator() {
            if ((ch & SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }

    static final class Ints implements Spliterator.OfInt {
        private final int[] a;
        private int i;
        private final int hi;
        private final int ch;

        Ints(int[] a, int from, int to, int ch) {
            this.a = a;
            this.i = from;
            this.hi = to;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            if (i < hi) {
                action.accept(a[i++]);
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            int[] arr = a;
            int end = hi;
            int from = i;
            i = end;
            for (int k = from; k < end; k++) {
                action.accept(arr[k]);
            }
        }

        @Override
        public Spliterator.OfInt trySplit() {
            int lo = i;
            int mid = (lo + hi) >>> 1;
            if (lo >= mid) {
                return null;
            }
            i = mid;
            return new Ints(a, lo, mid, ch);
        }

        @Override
        public long estimateSize() { return hi - i; }

        @Override
        public int characteristics() { return ch; }

        @Override
        public Comparator<? super Integer> getComparator() {
            if ((ch & SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }

    static final class Longs implements Spliterator.OfLong {
        private final long[] a;
        private int i;
        private final int hi;
        private final int ch;

        Longs(long[] a, int from, int to, int ch) {
            this.a = a;
            this.i = from;
            this.hi = to;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            if (i < hi) {
                action.accept(a[i++]);
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
            long[] arr = a;
            int end = hi;
            int from = i;
            i = end;
            for (int k = from; k < end; k++) {
                action.accept(arr[k]);
            }
        }

        @Override
        public Spliterator.OfLong trySplit() {
            int lo = i;
            int mid = (lo + hi) >>> 1;
            if (lo >= mid) {
                return null;
            }
            i = mid;
            return new Longs(a, lo, mid, ch);
        }

        @Override
        public long estimateSize() { return hi - i; }

        @Override
        public int characteristics() { return ch; }

        @Override
        public Comparator<? super Long> getComparator() {
            if ((ch & SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }

    static final class Doubles implements Spliterator.OfDouble {
        private final double[] a;
        private int i;
        private final int hi;
        private final int ch;

        Doubles(double[] a, int from, int to, int ch) {
            this.a = a;
            this.i = from;
            this.hi = to;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            Objects.requireNonNull(action);
            if (i < hi) {
                action.accept(a[i++]);
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            Objects.requireNonNull(action);
            double[] arr = a;
            int end = hi;
            int from = i;
            i = end;
            for (int k = from; k < end; k++) {
                action.accept(arr[k]);
            }
        }

        @Override
        public Spliterator.OfDouble trySplit() {
            int lo = i;
            int mid = (lo + hi) >>> 1;
            if (lo >= mid) {
                return null;
            }
            i = mid;
            return new Doubles(a, lo, mid, ch);
        }

        @Override
        public long estimateSize() { return hi - i; }

        @Override
        public int characteristics() { return ch; }

        @Override
        public Comparator<? super Double> getComparator() {
            if ((ch & SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }
}
