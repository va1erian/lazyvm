package java.util.stream;

import java.util.Arrays;
import java.util.Spliterator;
import java.util.Spliterators;

/** The builders: they collect into an array and the built stream reads it. */
final class Builders {
    private Builders() {
    }

    /** A built stream of at most one element is reported as immutable; larger ones are not. */
    private static int flags(int n) {
        return n <= 1 ? Spliterator.ORDERED | Spliterator.IMMUTABLE : Spliterator.ORDERED;
    }

    static final class Refs<T> implements Stream.Builder<T> {
        private Object[] a = new Object[8];
        private int n;
        private boolean built;

        @Override
        public void accept(T t) {
            if (built) {
                throw new IllegalStateException();
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = t;
        }

        @Override
        public Stream<T> build() {
            if (built) {
                throw new IllegalStateException();
            }
            built = true;
            return StreamSupport.stream(Spliterators.<T>spliterator(a, 0, n, flags(n)), false);
        }
    }

    static final class Ints implements IntStream.Builder {
        private int[] a = new int[8];
        private int n;
        private boolean built;

        @Override
        public void accept(int t) {
            if (built) {
                throw new IllegalStateException();
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = t;
        }

        @Override
        public IntStream build() {
            if (built) {
                throw new IllegalStateException();
            }
            built = true;
            return StreamSupport.intStream(Spliterators.spliterator(a, 0, n, flags(n)), false);
        }
    }

    static final class Longs implements LongStream.Builder {
        private long[] a = new long[8];
        private int n;
        private boolean built;

        @Override
        public void accept(long t) {
            if (built) {
                throw new IllegalStateException();
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = t;
        }

        @Override
        public LongStream build() {
            if (built) {
                throw new IllegalStateException();
            }
            built = true;
            return StreamSupport.longStream(Spliterators.spliterator(a, 0, n, flags(n)), false);
        }
    }

    static final class Doubles implements DoubleStream.Builder {
        private double[] a = new double[8];
        private int n;
        private boolean built;

        @Override
        public void accept(double t) {
            if (built) {
                throw new IllegalStateException();
            }
            if (n == a.length) {
                a = Arrays.copyOf(a, n * 2);
            }
            a[n++] = t;
        }

        @Override
        public DoubleStream build() {
            if (built) {
                throw new IllegalStateException();
            }
            built = true;
            return StreamSupport.doubleStream(Spliterators.spliterator(a, 0, n, flags(n)), false);
        }
    }
}
