package java.util.stream;

import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** The spliterators behind range and rangeClosed. */
final class Ranges {
    private Ranges() {
    }

    private static final int FLAGS = Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED
            | Spliterator.NONNULL | Spliterator.IMMUTABLE;

    static Spliterator.OfLong longs(long first, long last) { return new Longs(first, last); }

    /** Lets a range drop n values at once; returns how many are still to skip (all n for any other source). */
    static long skipAhead(Spliterator<?> sp, long n) {
        if (sp instanceof Ints) {
            return ((Ints) sp).skip(n);
        }
        return sp instanceof Longs ? ((Longs) sp).skip(n) : n;
    }

    /** int values lo (inclusive) up to hi (exclusive), held as longs so the type's last value needs no care. */
    static final class Ints implements Spliterator.OfInt {
        private long lo;
        private final long hi;

        Ints(long lo, long hi) {
            this.lo = lo;
            this.hi = hi;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            if (lo < hi) {
                action.accept((int) lo++);
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            long i = lo;
            lo = hi;
            for (; i < hi; i++) {
                action.accept((int) i);
            }
        }

        /** Drops up to n values at once; returns how many were not there to drop. */
        long skip(long n) {
            long take = Math.min(n, hi - lo);
            lo += take;
            return n - take;
        }

        @Override
        public Spliterator.OfInt trySplit() {
            long half = (hi - lo) >>> 1;
            if (half == 0) {
                return null;
            }
            Ints prefix = new Ints(lo, lo + half);
            lo += half;
            return prefix;
        }

        @Override
        public long estimateSize() { return hi - lo; }

        @Override
        public int characteristics() { return FLAGS | Spliterator.SIZED | Spliterator.SUBSIZED; }

        @Override
        public Comparator<? super Integer> getComparator() { return null; }
    }

    /** long values first..last inclusive; the span may be larger than a long can count. */
    static final class Longs implements Spliterator.OfLong {
        private long next;
        private final long last;
        private boolean more = true;

        Longs(long first, long last) {
            this.next = first;
            this.last = last;
        }

        private boolean fits() {
            long d = last - next;
            return d >= 0 && d != Long.MAX_VALUE;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            if (!more) {
                return false;
            }
            long x = next;
            if (x == last) {
                more = false;
            } else {
                next++;
            }
            action.accept(x);
            return true;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
            if (more) {
                more = false;
                long x = next;
                for (;;) {
                    action.accept(x);
                    if (x == last) {
                        break;
                    }
                    x++;
                }
            }
        }

        /** Drops up to n values at once; returns how many were not there to drop. */
        long skip(long n) {
            if (!more || n == 0) {
                return n;
            }
            if (fits() && n >= last - next + 1) {
                more = false;
                return n - (last - next + 1);
            }
            next += n;
            return 0;
        }

        @Override
        public Spliterator.OfLong trySplit() {
            if (!more) {
                return null;
            }
            long d = last - next;
            long half = (d >>> 1) + (d & 1);
            if (half == 0) {
                return null;
            }
            Longs prefix = new Longs(next, next + half - 1);
            next += half;
            return prefix;
        }

        @Override
        public long estimateSize() {
            if (!more) {
                return 0;
            }
            return fits() ? last - next + 1 : Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            if (!more || fits()) {
                return FLAGS | Spliterator.SIZED | Spliterator.SUBSIZED;
            }
            // A span too long to count is not known to be sorted or distinct either.
            return Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.IMMUTABLE;
        }

        @Override
        public Comparator<? super Long> getComparator() { return null; }
    }
}
