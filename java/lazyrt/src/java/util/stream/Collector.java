package java.util.stream;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

public interface Collector<T, A, R> {
    Supplier<A> supplier();

    BiConsumer<A, T> accumulator();

    BinaryOperator<A> combiner();

    Function<A, R> finisher();

    Set<Characteristics> characteristics();

    static <T, R> Collector<T, R, R> of(Supplier<R> supplier, BiConsumer<R, T> accumulator,
            BinaryOperator<R> combiner, Characteristics... characteristics) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        Objects.requireNonNull(characteristics);
        Set<Characteristics> extra = Parts.traits(characteristics);
        EnumSet<Characteristics> all = EnumSet.of(Characteristics.IDENTITY_FINISH);
        all.addAll(extra);
        return Parts.direct(supplier, accumulator, combiner, Collections.unmodifiableSet(all));
    }

    static <T, A, R> Collector<T, A, R> of(Supplier<A> supplier, BiConsumer<A, T> accumulator,
            BinaryOperator<A> combiner, Function<A, R> finisher, Characteristics... characteristics) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        Objects.requireNonNull(finisher);
        Objects.requireNonNull(characteristics);
        return new Parts<T, A, R>(supplier, accumulator, combiner, finisher, Parts.traits(characteristics));
    }

    enum Characteristics {
        CONCURRENT,
        UNORDERED,
        IDENTITY_FINISH
    }
}
