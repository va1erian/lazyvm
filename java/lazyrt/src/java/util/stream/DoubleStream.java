package java.util.stream;

import java.util.DoubleSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalDouble;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoublePredicate;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleToLongFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;

public interface DoubleStream extends BaseStream<Double, DoubleStream> {
    DoubleStream filter(DoublePredicate predicate);

    DoubleStream map(DoubleUnaryOperator mapper);

    <U> Stream<U> mapToObj(DoubleFunction<? extends U> mapper);

    IntStream mapToInt(DoubleToIntFunction mapper);

    LongStream mapToLong(DoubleToLongFunction mapper);

    DoubleStream flatMap(DoubleFunction<? extends DoubleStream> mapper);

    DoubleStream distinct();

    DoubleStream sorted();

    DoubleStream peek(DoubleConsumer action);

    DoubleStream limit(long maxSize);

    DoubleStream skip(long n);

    void forEach(DoubleConsumer action);

    void forEachOrdered(DoubleConsumer action);

    double[] toArray();

    double reduce(double identity, DoubleBinaryOperator op);

    OptionalDouble reduce(DoubleBinaryOperator op);

    <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator, BiConsumer<R, R> combiner);

    double sum();

    OptionalDouble min();

    OptionalDouble max();

    long count();

    OptionalDouble average();

    DoubleSummaryStatistics summaryStatistics();

    boolean anyMatch(DoublePredicate predicate);

    boolean allMatch(DoublePredicate predicate);

    boolean noneMatch(DoublePredicate predicate);

    OptionalDouble findFirst();

    OptionalDouble findAny();

    Stream<Double> boxed();

    @Override
    DoubleStream sequential();

    @Override
    DoubleStream parallel();

    @Override
    PrimitiveIterator.OfDouble iterator();

    @Override
    Spliterator.OfDouble spliterator();

    static Builder builder() {
        return new Builders.Doubles();
    }

    static DoubleStream empty() {
        return StreamSupport.doubleStream(java.util.Spliterators.emptyDoubleSpliterator(), false);
    }

    static DoubleStream of(double t) {
        return StreamSupport.doubleStream(java.util.Arrays.spliterator(new double[] {t}), false);
    }

    static DoubleStream of(double... values) {
        return java.util.Arrays.stream(values);
    }

    static DoubleStream iterate(final double seed, final DoubleUnaryOperator f) {
        Objects.requireNonNull(f);
        return Heads.iterateDouble(seed, f);
    }

    static DoubleStream generate(DoubleSupplier s) {
        Objects.requireNonNull(s);
        return Heads.generateDouble(s);
    }

    static DoubleStream concat(DoubleStream a, DoubleStream b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        return Heads.concatDouble(a, b);
    }

    interface Builder extends DoubleConsumer {
        @Override
        void accept(double t);

        default Builder add(double t) {
            accept(t);
            return this;
        }

        DoubleStream build();
    }
}
