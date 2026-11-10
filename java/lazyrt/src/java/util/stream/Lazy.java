package java.util.stream;

import java.util.Comparator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * The spliterator() of a stream that starts from a supplier: it asks the supplier the first time anything is asked
 * of it, and from then on it is the spliterator the supplier made (docs/design/streams.md SP6).
 */
final class Lazy {
    private Lazy() {
    }

    static Spliterator<?> of(int kind, Supplier<? extends Spliterator<?>> maker) {
        switch (kind) {
            case Pipe.INTS: return new Ints(maker);
            case Pipe.LONGS: return new Longs(maker);
            case Pipe.DOUBLES: return new Doubles(maker);
            default: return new Refs<Object>(maker);
        }
    }

    /** Holds the supplier until the made spliterator is needed. */
    private abstract static class Base<S extends Spliterator<?>> {
        private Supplier<? extends Spliterator<?>> maker;
        private S made;

        Base(Supplier<? extends Spliterator<?>> maker) { this.maker = maker; }

        @SuppressWarnings("unchecked")
        final S made() {
            if (made == null) {
                made = (S) Objects.requireNonNull(maker.get());
                maker = null;
            }
            return made;
        }

        public long estimateSize() { return made().estimateSize(); }

        public int characteristics() { return made().characteristics(); }

        public long getExactSizeIfKnown() { return made().getExactSizeIfKnown(); }
    }

    static final class Refs<T> extends Base<Spliterator<T>> implements Spliterator<T> {
        Refs(Supplier<? extends Spliterator<?>> maker) { super(maker); }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) { return made().tryAdvance(action); }

        @Override
        public void forEachRemaining(Consumer<? super T> action) { made().forEachRemaining(action); }

        @Override
        public Spliterator<T> trySplit() { return made().trySplit(); }

        @Override
        public Comparator<? super T> getComparator() { return made().getComparator(); }
    }

    static final class Ints extends Base<Spliterator.OfInt> implements Spliterator.OfInt {
        Ints(Supplier<? extends Spliterator<?>> maker) { super(maker); }

        @Override
        public boolean tryAdvance(IntConsumer action) { return made().tryAdvance(action); }

        @Override
        public void forEachRemaining(IntConsumer action) { made().forEachRemaining(action); }

        @Override
        public Spliterator.OfInt trySplit() { return made().trySplit(); }

        @Override
        public Comparator<? super Integer> getComparator() { return made().getComparator(); }
    }

    static final class Longs extends Base<Spliterator.OfLong> implements Spliterator.OfLong {
        Longs(Supplier<? extends Spliterator<?>> maker) { super(maker); }

        @Override
        public boolean tryAdvance(LongConsumer action) { return made().tryAdvance(action); }

        @Override
        public void forEachRemaining(LongConsumer action) { made().forEachRemaining(action); }

        @Override
        public Spliterator.OfLong trySplit() { return made().trySplit(); }

        @Override
        public Comparator<? super Long> getComparator() { return made().getComparator(); }
    }

    static final class Doubles extends Base<Spliterator.OfDouble> implements Spliterator.OfDouble {
        Doubles(Supplier<? extends Spliterator<?>> maker) { super(maker); }

        @Override
        public boolean tryAdvance(DoubleConsumer action) { return made().tryAdvance(action); }

        @Override
        public void forEachRemaining(DoubleConsumer action) { made().forEachRemaining(action); }

        @Override
        public Spliterator.OfDouble trySplit() { return made().trySplit(); }

        @Override
        public Comparator<? super Double> getComparator() { return made().getComparator(); }
    }
}
