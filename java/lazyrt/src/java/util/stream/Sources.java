package java.util.stream;

import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** The spliterators behind iterate, generate and concat. None of them splits. */
final class Sources {
    private Sources() {
    }

    /** What an endless source reports: no size, and no values handed out twice. */
    abstract static class Endless {
        private final int ch;

        Endless(int ch) { this.ch = ch; }

        public long estimateSize() { return Long.MAX_VALUE; }

        public int characteristics() { return ch; }
    }

    private static final int SEQUENCE = Spliterator.ORDERED | Spliterator.IMMUTABLE;
    private static final int PRIM_SEQUENCE = SEQUENCE | Spliterator.NONNULL;
    private static final int GENERATED = Spliterator.IMMUTABLE;

    /** seed, f(seed), f(f(seed)) ...: f runs when the element after the one handed out is asked for. */
    static final class Iterate<T> extends Endless implements Spliterator<T> {
        private final UnaryOperator<T> f;
        private T last;
        private boolean started;

        Iterate(T seed, UnaryOperator<T> f) {
            super(SEQUENCE);
            this.last = seed;
            this.f = f;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            java.util.Objects.requireNonNull(action);
            last = started ? f.apply(last) : last;
            started = true;
            action.accept(last);
            return true;
        }

        @Override
        public Spliterator<T> trySplit() { return null; }
    }

    static final class Generate<T> extends Endless implements Spliterator<T> {
        private final Supplier<? extends T> s;

        Generate(Supplier<? extends T> s) {
            super(GENERATED);
            this.s = s;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            java.util.Objects.requireNonNull(action);
            action.accept(s.get());
            return true;
        }

        @Override
        public Spliterator<T> trySplit() { return null; }
    }

    /** The primitive iterate computes the next value before it hands the current one out. */
    static final class IterateInt extends Endless implements Spliterator.OfInt {
        private final IntUnaryOperator f;
        private int current;

        IterateInt(int seed, IntUnaryOperator f) {
            super(PRIM_SEQUENCE);
            this.current = seed;
            this.f = f;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            java.util.Objects.requireNonNull(action);
            int v = current;
            current = f.applyAsInt(v);
            action.accept(v);
            return true;
        }

        @Override
        public Spliterator.OfInt trySplit() { return null; }
    }

    static final class IterateLong extends Endless implements Spliterator.OfLong {
        private final LongUnaryOperator f;
        private long current;

        IterateLong(long seed, LongUnaryOperator f) {
            super(PRIM_SEQUENCE);
            this.current = seed;
            this.f = f;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            java.util.Objects.requireNonNull(action);
            long v = current;
            current = f.applyAsLong(v);
            action.accept(v);
            return true;
        }

        @Override
        public Spliterator.OfLong trySplit() { return null; }
    }

    static final class IterateDouble extends Endless implements Spliterator.OfDouble {
        private final DoubleUnaryOperator f;
        private double current;

        IterateDouble(double seed, DoubleUnaryOperator f) {
            super(PRIM_SEQUENCE);
            this.current = seed;
            this.f = f;
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            java.util.Objects.requireNonNull(action);
            double v = current;
            current = f.applyAsDouble(v);
            action.accept(v);
            return true;
        }

        @Override
        public Spliterator.OfDouble trySplit() { return null; }
    }

    static final class GenerateInt extends Endless implements Spliterator.OfInt {
        private final IntSupplier s;

        GenerateInt(IntSupplier s) {
            super(GENERATED);
            this.s = s;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            java.util.Objects.requireNonNull(action);
            action.accept(s.getAsInt());
            return true;
        }

        @Override
        public Spliterator.OfInt trySplit() { return null; }
    }

    static final class GenerateLong extends Endless implements Spliterator.OfLong {
        private final LongSupplier s;

        GenerateLong(LongSupplier s) {
            super(GENERATED);
            this.s = s;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            java.util.Objects.requireNonNull(action);
            action.accept(s.getAsLong());
            return true;
        }

        @Override
        public Spliterator.OfLong trySplit() { return null; }
    }

    static final class GenerateDouble extends Endless implements Spliterator.OfDouble {
        private final DoubleSupplier s;

        GenerateDouble(DoubleSupplier s) {
            super(GENERATED);
            this.s = s;
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            java.util.Objects.requireNonNull(action);
            action.accept(s.getAsDouble());
            return true;
        }

        @Override
        public Spliterator.OfDouble trySplit() { return null; }
    }

    // ---- concat ----

    /** Two spliterators one after the other. What is reported follows what is left: see SP12. */
    abstract static class Join<T, S extends Spliterator<T>> {
        private static final int COMMON = Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED
                | Spliterator.IMMUTABLE | Spliterator.NONNULL;
        final S first;
        final S second;
        /** True once the first part is used up (or was handed out by trySplit). */
        boolean inSecond;
        /** What the pair reports while the first part is still there, worked out once when it is made. */
        private final int together;

        Join(S first, S second) {
            this.first = first;
            this.second = second;
            int ch = first.characteristics() & second.characteristics() & COMMON;
            boolean overflow = first.estimateSize() + second.estimateSize() < 0;
            this.together = overflow ? ch & ~(Spliterator.SIZED | Spliterator.SUBSIZED) : ch;
        }

        public long estimateSize() {
            if (inSecond) {
                return second.estimateSize();
            }
            long n = first.estimateSize() + second.estimateSize();
            return n < 0 ? Long.MAX_VALUE : n;
        }

        public int characteristics() { return inSecond ? second.characteristics() : together; }

        /** The first part as the prefix, then what is left is the second part. */
        @SuppressWarnings("unchecked")
        S split() {
            if (inSecond) {
                return (S) second.trySplit();
            }
            inSecond = true;
            return first;
        }
    }

    static final class JoinRefs<T> extends Join<T, Spliterator<T>> implements Spliterator<T> {
        JoinRefs(Spliterator<T> a, Spliterator<T> b) { super(a, b); }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (!inSecond && first.tryAdvance(action)) {
                return true;
            }
            inSecond = true;
            return second.tryAdvance(action);
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            if (!inSecond) {
                inSecond = true;
                first.forEachRemaining(action);
            }
            second.forEachRemaining(action);
        }

        @Override
        public Spliterator<T> trySplit() { return split(); }

        @Override
        public Comparator<? super T> getComparator() { throw new IllegalStateException(); }
    }

    static final class JoinInts extends Join<Integer, Spliterator.OfInt> implements Spliterator.OfInt {
        JoinInts(Spliterator.OfInt a, Spliterator.OfInt b) { super(a, b); }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            if (!inSecond && first.tryAdvance(action)) {
                return true;
            }
            inSecond = true;
            return second.tryAdvance(action);
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            if (!inSecond) {
                inSecond = true;
                first.forEachRemaining(action);
            }
            second.forEachRemaining(action);
        }

        @Override
        public Spliterator.OfInt trySplit() { return split(); }
    }

    static final class JoinLongs extends Join<Long, Spliterator.OfLong> implements Spliterator.OfLong {
        JoinLongs(Spliterator.OfLong a, Spliterator.OfLong b) { super(a, b); }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            if (!inSecond && first.tryAdvance(action)) {
                return true;
            }
            inSecond = true;
            return second.tryAdvance(action);
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            if (!inSecond) {
                inSecond = true;
                first.forEachRemaining(action);
            }
            second.forEachRemaining(action);
        }

        @Override
        public Spliterator.OfLong trySplit() { return split(); }
    }

    static final class JoinDoubles extends Join<Double, Spliterator.OfDouble> implements Spliterator.OfDouble {
        JoinDoubles(Spliterator.OfDouble a, Spliterator.OfDouble b) { super(a, b); }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            if (!inSecond && first.tryAdvance(action)) {
                return true;
            }
            inSecond = true;
            return second.tryAdvance(action);
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            if (!inSecond) {
                inSecond = true;
                first.forEachRemaining(action);
            }
            second.forEachRemaining(action);
        }

        @Override
        public Spliterator.OfDouble trySplit() { return split(); }
    }
}
