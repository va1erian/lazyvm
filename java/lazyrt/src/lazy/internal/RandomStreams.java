package lazy.internal;

import java.util.Objects;
import java.util.Random;
import java.util.Spliterator;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * The streams of Random: each element is drawn from the generator when the stream asks for it, by calling
 * the generator's public methods, so a subclass that overrides them changes the stream.
 */
public final class RandomStreams {
    private RandomStreams() {
    }

    private static final int FLAGS = Spliterator.SIZED | Spliterator.NONNULL | Spliterator.IMMUTABLE
            | Spliterator.SUBSIZED;

    private static long size(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        return n;
    }

    /** The largest double under d; d is never NaN or -Infinity here. */
    private static double below(double d) {
        if (d == 0.0) {
            return -Double.MIN_VALUE;
        }
        long bits = Double.doubleToRawLongBits(d);
        return Double.longBitsToDouble(d > 0 ? bits - 1 : bits + 1);
    }

    private static void order(boolean ok) {
        if (!ok) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
    }

    public static IntStream ints(Random r, long n) {
        return StreamSupport.intStream(new Ints(r, size(n), 0, 0, false), false);
    }

    public static IntStream ints(Random r, long n, int origin, int bound) {
        size(n);
        order(origin < bound);
        return StreamSupport.intStream(new Ints(r, n, origin, bound, true), false);
    }

    public static LongStream longs(Random r, long n) {
        return StreamSupport.longStream(new Longs(r, size(n), 0, 0, false), false);
    }

    public static LongStream longs(Random r, long n, long origin, long bound) {
        size(n);
        order(origin < bound);
        return StreamSupport.longStream(new Longs(r, n, origin, bound, true), false);
    }

    public static DoubleStream doubles(Random r, long n) {
        return StreamSupport.doubleStream(new Doubles(r, size(n), 0, 0, false), false);
    }

    public static DoubleStream doubles(Random r, long n, double origin, double bound) {
        size(n);
        order(origin < bound);
        return StreamSupport.doubleStream(new Doubles(r, n, origin, bound, true), false);
    }

    private static final class Ints implements Spliterator.OfInt {
        private final Random r;
        private long left;
        private final int origin;
        private final int bound;
        private final boolean bounded;

        Ints(Random r, long left, int origin, int bound, boolean bounded) {
            this.r = r;
            this.left = left;
            this.origin = origin;
            this.bound = bound;
            this.bounded = bounded;
        }

        private int draw() {
            if (!bounded) {
                return r.nextInt();
            }
            int n = bound - origin;
            if (n > 0) {
                return r.nextInt(n) + origin;
            }
            int x;
            do {
                x = r.nextInt();
            } while (x < origin || x >= bound);
            return x;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            if (left <= 0) {
                return false;
            }
            left--;
            action.accept(draw());
            return true;
        }

        @Override
        public Spliterator.OfInt trySplit() { return null; }

        @Override
        public long estimateSize() { return left; }

        @Override
        public int characteristics() { return FLAGS; }
    }

    private static final class Longs implements Spliterator.OfLong {
        private final Random r;
        private long left;
        private final long origin;
        private final long bound;
        private final boolean bounded;

        Longs(Random r, long left, long origin, long bound, boolean bounded) {
            this.r = r;
            this.left = left;
            this.origin = origin;
            this.bound = bound;
            this.bounded = bounded;
        }

        private long draw() {
            long x = r.nextLong();
            if (!bounded) {
                return x;
            }
            long n = bound - origin;
            long m = n - 1;
            if ((n & m) == 0L) {
                return (x & m) + origin;
            }
            if (n > 0L) {
                for (long u = x >>> 1; u + m - (x = u % n) < 0L; u = r.nextLong() >>> 1) {
                    continue;
                }
                return x + origin;
            }
            while (x < origin || x >= bound) {
                x = r.nextLong();
            }
            return x;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            if (left <= 0) {
                return false;
            }
            left--;
            action.accept(draw());
            return true;
        }

        @Override
        public Spliterator.OfLong trySplit() { return null; }

        @Override
        public long estimateSize() { return left; }

        @Override
        public int characteristics() { return FLAGS; }
    }

    private static final class Doubles implements Spliterator.OfDouble {
        private final Random r;
        private long left;
        private final double origin;
        private final double bound;
        private final boolean bounded;

        Doubles(Random r, long left, double origin, double bound, boolean bounded) {
            this.r = r;
            this.left = left;
            this.origin = origin;
            this.bound = bound;
            this.bounded = bounded;
        }

        private double draw() {
            double x = r.nextDouble();
            if (!bounded) {
                return x;
            }
            x = x * (bound - origin) + origin;
            return x >= bound ? below(bound) : x;
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            Objects.requireNonNull(action);
            if (left <= 0) {
                return false;
            }
            left--;
            action.accept(draw());
            return true;
        }

        @Override
        public Spliterator.OfDouble trySplit() { return null; }

        @Override
        public long estimateSize() { return left; }

        @Override
        public int characteristics() { return FLAGS; }
    }
}
