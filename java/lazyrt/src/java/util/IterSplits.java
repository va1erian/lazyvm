package java.util;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

/** Spliterators over an iterator (a collection's late-binding one is Late). */
final class IterSplits {
    private IterSplits() {
    }

    static final class Refs<T> extends Spliterators.AbstractSpliterator<T> {
        private final Iterator<? extends T> it;
        private final int ch;

        Refs(Iterator<? extends T> it, long est, int ch) {
            super(est, 0);
            this.it = it;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            if (it.hasNext()) {
                action.accept(it.next());
                return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public void forEachRemaining(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            ((Iterator) it).forEachRemaining(action);
        }

        @Override
        public int characteristics() { return ch; }

        @Override
        public Comparator<? super T> getComparator() {
            if ((ch & SORTED) != 0) {
                return null;
            }
            throw new IllegalStateException();
        }
    }

    static final class Ints extends Spliterators.AbstractIntSpliterator {
        private final PrimitiveIterator.OfInt it;
        private final int ch;

        Ints(PrimitiveIterator.OfInt it, long est, int ch) {
            super(est, 0);
            this.it = it;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(IntConsumer action) {
            Objects.requireNonNull(action);
            if (it.hasNext()) {
                action.accept(it.nextInt());
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            Objects.requireNonNull(action);
            it.forEachRemaining(action);
        }

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

    static final class Longs extends Spliterators.AbstractLongSpliterator {
        private final PrimitiveIterator.OfLong it;
        private final int ch;

        Longs(PrimitiveIterator.OfLong it, long est, int ch) {
            super(est, 0);
            this.it = it;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            Objects.requireNonNull(action);
            if (it.hasNext()) {
                action.accept(it.nextLong());
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            Objects.requireNonNull(action);
            it.forEachRemaining(action);
        }

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

    static final class Doubles extends Spliterators.AbstractDoubleSpliterator {
        private final PrimitiveIterator.OfDouble it;
        private final int ch;

        Doubles(PrimitiveIterator.OfDouble it, long est, int ch) {
            super(est, 0);
            this.it = it;
            this.ch = ch;
        }

        @Override
        public boolean tryAdvance(DoubleConsumer action) {
            Objects.requireNonNull(action);
            if (it.hasNext()) {
                action.accept(it.nextDouble());
                return true;
            }
            return false;
        }

        @Override
        public void forEachRemaining(DoubleConsumer action) {
            Objects.requireNonNull(action);
            it.forEachRemaining(action);
        }

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
