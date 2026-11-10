package java.util.stream;

import java.util.IntSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

public interface IntStream extends BaseStream<Integer, IntStream> {
    IntStream filter(IntPredicate predicate);

    IntStream map(IntUnaryOperator mapper);

    <U> Stream<U> mapToObj(IntFunction<? extends U> mapper);

    LongStream mapToLong(IntToLongFunction mapper);

    DoubleStream mapToDouble(IntToDoubleFunction mapper);

    IntStream flatMap(IntFunction<? extends IntStream> mapper);

    IntStream distinct();

    IntStream sorted();

    IntStream peek(IntConsumer action);

    IntStream limit(long maxSize);

    IntStream skip(long n);

    void forEach(IntConsumer action);

    void forEachOrdered(IntConsumer action);

    int[] toArray();

    int reduce(int identity, IntBinaryOperator op);

    OptionalInt reduce(IntBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner);

    int sum();

    OptionalInt min();

    OptionalInt max();

    long count();

    OptionalDouble average();

    IntSummaryStatistics summaryStatistics();

    boolean anyMatch(IntPredicate predicate);

    boolean allMatch(IntPredicate predicate);

    boolean noneMatch(IntPredicate predicate);

    OptionalInt findFirst();

    OptionalInt findAny();

    LongStream asLongStream();

    DoubleStream asDoubleStream();

    Stream<Integer> boxed();

    @Override
    IntStream sequential();

    @Override
    IntStream parallel();

    @Override
    PrimitiveIterator.OfInt iterator();

    @Override
    Spliterator.OfInt spliterator();

    static Builder builder() {
        return new Builders.Ints();
    }

    static IntStream empty() {
        return StreamSupport.intStream(java.util.Spliterators.emptyIntSpliterator(), false);
    }

    static IntStream of(int t) {
        return StreamSupport.intStream(java.util.Arrays.spliterator(new int[] {t}), false);
    }

    static IntStream of(int... values) {
        return java.util.Arrays.stream(values);
    }

    static IntStream iterate(final int seed, final IntUnaryOperator f) {
        Objects.requireNonNull(f);
        return Heads.iterateInt(seed, f);
    }

    static IntStream generate(IntSupplier s) {
        Objects.requireNonNull(s);
        return Heads.generateInt(s);
    }

    static IntStream range(int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return empty();
        }
        return StreamSupport.intStream(new Ranges.Ints(startInclusive, endExclusive), false);
    }

    static IntStream rangeClosed(int startInclusive, int endInclusive) {
        if (startInclusive > endInclusive) {
            return empty();
        }
        return StreamSupport.intStream(new Ranges.Ints(startInclusive, (long) endInclusive + 1), false);
    }

    static IntStream concat(IntStream a, IntStream b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        return Heads.concatInt(a, b);
    }

    interface Builder extends IntConsumer {
        @Override
        void accept(int t);

        default Builder add(int t) {
            accept(t);
            return this;
        }

        IntStream build();
    }
}
