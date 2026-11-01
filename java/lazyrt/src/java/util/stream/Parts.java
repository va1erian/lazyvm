package java.util.stream;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/** A collector held as its five parts, handed back unchanged by every getter (docs/design/streams.md K7). */
final class Parts<T, A, R> implements Collector<T, A, R> {
    private final Supplier<A> supplier;
    private final BiConsumer<A, T> accumulator;
    private final BinaryOperator<A> combiner;
    private final Function<A, R> finisher;
    private final Set<Characteristics> traits;

    Parts(Supplier<A> supplier, BiConsumer<A, T> accumulator, BinaryOperator<A> combiner,
            Function<A, R> finisher, Set<Characteristics> traits) {
        this.supplier = supplier;
        this.accumulator = accumulator;
        this.combiner = combiner;
        this.finisher = finisher;
        this.traits = traits;
    }

    /** A collector whose container is its result: the finisher is the identity function. */
    @SuppressWarnings("unchecked")
    static <T, R> Parts<T, R, R> direct(Supplier<R> supplier, BiConsumer<R, T> accumulator,
            BinaryOperator<R> combiner, Set<Characteristics> traits) {
        return new Parts<T, R, R>(supplier, accumulator, combiner, Function.identity(), traits);
    }

    /** An unmodifiable set of the given traits; it prints in the order of the enum. */
    static Set<Characteristics> traits(Characteristics... which) {
        EnumSet<Characteristics> set = EnumSet.noneOf(Characteristics.class);
        for (Characteristics c : which) {
            set.add(c);
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public Supplier<A> supplier() { return supplier; }

    @Override
    public BiConsumer<A, T> accumulator() { return accumulator; }

    @Override
    public BinaryOperator<A> combiner() { return combiner; }

    @Override
    public Function<A, R> finisher() { return finisher; }

    @Override
    public Set<Characteristics> characteristics() { return traits; }
}
