package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** The shared spliterators that have nothing to deliver. */
final class Empties {
    private Empties() {
    }

    /** What all four share: size 0, never splits. */
    abstract static class Nil<T, S extends Spliterator<T>> {
        public S trySplit() { return null; }

        public long estimateSize() { return 0; }

        public int characteristics() { return Spliterator.SIZED | Spliterator.SUBSIZED; }
    }

    static final class Refs extends Nil<Object, Spliterator<Object>> implements Spliterator<Object> {
        @Override
        public boolean tryAdvance(Consumer<? super Object> action) {
            Objects.requireNonNull(action);
            return false;
        }

        @Override
        public void forEachRemaining(Consumer<? super Object> action) {
            Objects.requireNonNull(action);
        }
    }

    static final class Ints extends Nil<Integer, Spliterator.OfInt> implements Spliterator.OfInt {
        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            return false;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
        }
    }

    static final class Longs extends Nil<Long, Spliterator.OfLong> implements Spliterator.OfLong {
        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            return false;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
        }
    }

    static final class Doubles extends Nil<Double, Spliterator.OfDouble> implements Spliterator.OfDouble {
        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            Objects.requireNonNull(action);
            return false;
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            Objects.requireNonNull(action);
        }
    }
}
