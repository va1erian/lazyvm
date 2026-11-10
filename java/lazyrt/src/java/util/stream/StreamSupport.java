package java.util.stream;

import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Supplier;

public final class StreamSupport {
    private StreamSupport() {
    }

    public static <T> Stream<T> stream(Spliterator<T> spliterator, boolean parallel) {
        Objects.requireNonNull(spliterator);
        return Heads.ofRefs(spliterator, parallel);
    }

    public static <T> Stream<T> stream(Supplier<? extends Spliterator<T>> supplier, int characteristics,
            boolean parallel) {
        Objects.requireNonNull(supplier);
        return Heads.ofRefs(supplier, characteristics, parallel);
    }

    public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
        Objects.requireNonNull(spliterator);
        return Heads.ofInts(spliterator, parallel);
    }

    public static IntStream intStream(Supplier<? extends Spliterator.OfInt> supplier, int characteristics,
            boolean parallel) {
        return Heads.ofInts(supplier, characteristics, parallel);
    }

    public static LongStream longStream(Spliterator.OfLong spliterator, boolean parallel) {
        Objects.requireNonNull(spliterator);
        return Heads.ofLongs(spliterator, parallel);
    }

    public static LongStream longStream(Supplier<? extends Spliterator.OfLong> supplier, int characteristics,
            boolean parallel) {
        return Heads.ofLongs(supplier, characteristics, parallel);
    }

    public static DoubleStream doubleStream(Spliterator.OfDouble spliterator, boolean parallel) {
        Objects.requireNonNull(spliterator);
        return Heads.ofDoubles(spliterator, parallel);
    }

    public static DoubleStream doubleStream(Supplier<? extends Spliterator.OfDouble> supplier,
            int characteristics, boolean parallel) {
        return Heads.ofDoubles(supplier, characteristics, parallel);
    }
}
