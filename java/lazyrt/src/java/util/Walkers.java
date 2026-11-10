package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/**
 * Iterators that walk a spliterator. The spliterator is asked for one element only when the iterator is asked
 * whether there is one, and the element it hands over waits here until next(). forEachRemaining stays the
 * interface default (a hasNext/next loop): a program can see that it steps the spliterator one element at a time.
 */
final class Walkers {
    private Walkers() {
    }

    /** Where an iterator stands: nothing fetched yet, an element waiting, or the end. */
    private static final int UNKNOWN = 0;
    private static final int WAITING = 1;
    private static final int ENDED = 2;

    static final class Refs<T> implements Iterator<T> {
        private final Spliterator<? extends T> sp;
        private final Consumer<T> keep = x -> held = x;
        private int state = UNKNOWN;
        private T held;

        Refs(Spliterator<? extends T> sp) { this.sp = sp; }

        @Override
        public boolean hasNext() {
            if (state == UNKNOWN) {
                state = sp.tryAdvance(keep) ? WAITING : ENDED;
            }
            return state == WAITING;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            state = UNKNOWN;
            T x = held;
            held = null;
            return x;
        }
    }

    static final class Ints implements PrimitiveIterator.OfInt {
        private final Spliterator.OfInt sp;
        private final IntConsumer keep = x -> held = x;
        private int state = UNKNOWN;
        private int held;

        Ints(Spliterator.OfInt sp) { this.sp = sp; }

        @Override
        public boolean hasNext() {
            if (state == UNKNOWN) {
                state = sp.tryAdvance(keep) ? WAITING : ENDED;
            }
            return state == WAITING;
        }

        @Override
        public int nextInt() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            state = UNKNOWN;
            return held;
        }
    }

    static final class Longs implements PrimitiveIterator.OfLong {
        private final Spliterator.OfLong sp;
        private final LongConsumer keep = x -> held = x;
        private int state = UNKNOWN;
        private long held;

        Longs(Spliterator.OfLong sp) { this.sp = sp; }

        @Override
        public boolean hasNext() {
            if (state == UNKNOWN) {
                state = sp.tryAdvance(keep) ? WAITING : ENDED;
            }
            return state == WAITING;
        }

        @Override
        public long nextLong() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            state = UNKNOWN;
            return held;
        }
    }

    static final class Doubles implements PrimitiveIterator.OfDouble {
        private final Spliterator.OfDouble sp;
        private final DoubleConsumer keep = x -> held = x;
        private int state = UNKNOWN;
        private double held;

        Doubles(Spliterator.OfDouble sp) { this.sp = sp; }

        @Override
        public boolean hasNext() {
            if (state == UNKNOWN) {
                state = sp.tryAdvance(keep) ? WAITING : ENDED;
            }
            return state == WAITING;
        }

        @Override
        public double nextDouble() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            state = UNKNOWN;
            return held;
        }
    }
}
