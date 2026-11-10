package java.util.stream;

import java.util.LongSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;

public interface LongStream extends BaseStream<Long, LongStream> {
    LongStream filter(LongPredicate predicate);

    LongStream map(LongUnaryOperator mapper);

    <U> Stream<U> mapToObj(LongFunction<? extends U> mapper);

    IntStream mapToInt(LongToIntFunction mapper);

    DoubleStream mapToDouble(LongToDoubleFunction mapper);

    LongStream flatMap(LongFunction<? extends LongStream> mapper);

    LongStream distinct();

    LongStream sorted();

    LongStream peek(LongConsumer action);

    LongStream limit(long maxSize);

    LongStream skip(long n);

    void forEach(LongConsumer action);

    void forEachOrdered(LongConsumer action);

    long[] toArray();

    long reduce(long identity, LongBinaryOperator op);

    OptionalLong reduce(LongBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjLongConsumer<R> accumulator, BiConsumer<R, R> combiner);

    long sum();

    OptionalLong min();

    OptionalLong max();

    long count();

    OptionalDouble average();

    LongSummaryStatistics summaryStatistics();

    boolean anyMatch(LongPredicate predicate);

    boolean allMatch(LongPredicate predicate);

    boolean noneMatch(LongPredicate predicate);

    OptionalLong findFirst();

    OptionalLong findAny();

    DoubleStream asDoubleStream();

    Stream<Long> boxed();

    @Override
    LongStream sequential();

    @Override
    LongStream parallel();

    @Override
    PrimitiveIterator.OfLong iterator();

    @Override
    Spliterator.OfLong spliterator();

    static Builder builder() {
        return new Builders.Longs();
    }

    static LongStream empty() {
        return StreamSupport.longStream(java.util.Spliterators.emptyLongSpliterator(), false);
    }

    static LongStream of(long t) {
        return StreamSupport.longStream(java.util.Arrays.spliterator(new long[] {t}), false);
    }

    static LongStream of(long... values) {
        return java.util.Arrays.stream(values);
    }

    static LongStream iterate(final long seed, final LongUnaryOperator f) {
        Objects.requireNonNull(f);
        return Heads.iterateLong(seed, f);
    }

    static LongStream generate(LongSupplier s) {
        Objects.requireNonNull(s);
        return Heads.generateLong(s);
    }

    static LongStream range(long startInclusive, long endExclusive) {
        if (startInclusive >= endExclusive) {
            return empty();
        }
        return StreamSupport.longStream(Ranges.longs(startInclusive, endExclusive - 1), false);
    }

    static LongStream rangeClosed(long startInclusive, long endInclusive) {
        if (startInclusive > endInclusive) {
            return empty();
        }
        return StreamSupport.longStream(Ranges.longs(startInclusive, endInclusive), false);
    }

    static LongStream concat(LongStream a, LongStream b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        return Heads.concatLong(a, b);
    }

    interface Builder extends LongConsumer {
        @Override
        void accept(long t);

        default Builder add(long t) {
            accept(t);
            return this;
        }

        LongStream build();
    }
}
