package java.util.stream;

import java.util.DoubleSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoublePredicate;
import java.util.function.DoubleToIntFunction;
import java.util.function.DoubleToLongFunction;
import java.util.function.DoubleUnaryOperator;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;

/** DoubleStream. */
final class DoubleFlow extends Flow<Double, DoubleStream> implements DoubleStream {
    private static final int REKEY = Spliterator.DISTINCT | Spliterator.SORTED;

    DoubleFlow(Pipe pipe, int flags, boolean natural) { super(pipe, flags, natural); }

    DoubleFlow(Flow<?, ?> from, Relay.Layer layer, int flags, boolean natural, boolean stops) {
        super(from, layer, flags, natural, stops);
    }

    @Override
    DoubleStream stage(Relay.Layer layer, int fl, boolean nat, boolean stops) {
        return new DoubleFlow(this, layer, fl, nat, stops);
    }

    @Override
    Relay.Layer sorting(boolean exact) { return next -> new Sorting.Doubles(next, exact); }

    @Override
    public DoubleStream filter(DoublePredicate predicate) {
        Objects.requireNonNull(predicate);
        return stage(next -> new Relay.Narrow(next) {
            @Override
            public void accept(double x) {
                if (predicate.test(x)) {
                    next.accept(x);
                }
            }
        }, keep(SIZES), natural, false);
    }

    @Override
    public DoubleStream map(DoubleUnaryOperator mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) { next.accept(mapper.applyAsDouble(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public <U> Stream<U> mapToObj(DoubleFunction<? extends U> mapper) {
        Objects.requireNonNull(mapper);
        return new RefFlow<U>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) { next.accept(mapper.apply(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public IntStream mapToInt(DoubleToIntFunction mapper) {
        Objects.requireNonNull(mapper);
        return new IntFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) { next.accept(mapper.applyAsInt(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public LongStream mapToLong(DoubleToLongFunction mapper) {
        Objects.requireNonNull(mapper);
        return new LongFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) { next.accept(mapper.applyAsLong(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public DoubleStream flatMap(DoubleFunction<? extends DoubleStream> mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Stages.Flat(next, x -> mapper.apply((Double) x)), keep(SIZES | REKEY), false,
                false);
    }

    @Override
    public DoubleStream distinct() {
        return stage(next -> new Stages.Hashed(next), keep(SIZES | REKEY), false, false);
    }

    @Override
    public DoubleStream peek(DoubleConsumer action) {
        Objects.requireNonNull(action);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) {
                action.accept(x);
                next.accept(x);
            }
        }, keep(0), natural, false);
    }

    @Override
    public void forEach(DoubleConsumer action) {
        link();
        Objects.requireNonNull(action);
        if (prev == null) {
            ((Spliterator.OfDouble) pipe.source()).forEachRemaining(action);
        } else if (action instanceof Relay) {
            exec((Relay) action, false);
        } else {
            exec(new Relay() {
                @Override
                public void accept(double x) { action.accept(x); }
            }, false);
        }
    }

    @Override
    public void forEachOrdered(DoubleConsumer action) { forEach(action); }

    @Override
    public double[] toArray() {
        link();
        Ends.Doubles ends = new Ends.Doubles(announce());
        exec(ends, false);
        return ends.result();
    }

    @Override
    public double reduce(double identity, DoubleBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldDouble f = new Ends.FoldDouble(identity, op);
        run(f, false);
        return f.acc;
    }

    @Override
    public OptionalDouble reduce(DoubleBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldDouble f = new Ends.FoldDouble(op);
        run(f, false);
        return f.any ? OptionalDouble.of(f.acc) : OptionalDouble.empty();
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjDoubleConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        link();
        R r = supplier.get();
        exec(new Relay() {
            @Override
            public void accept(double x) { accumulator.accept(r, x); }
        }, false);
        return r;
    }

    @Override
    public double sum() {
        DoubleSummaryStatistics st = new DoubleSummaryStatistics();
        forEach(st);
        return st.getSum();
    }

    @Override
    public OptionalDouble min() { return reduce(Math::min); }

    @Override
    public OptionalDouble max() { return reduce(Math::max); }

    @Override
    public long count() {
        Ends.Count c = new Ends.Count();
        run(c, false);
        return c.n;
    }

    @Override
    public OptionalDouble average() {
        DoubleSummaryStatistics st = summaryStatistics();
        return st.getCount() > 0 ? OptionalDouble.of(st.getAverage()) : OptionalDouble.empty();
    }

    @Override
    public DoubleSummaryStatistics summaryStatistics() {
        DoubleSummaryStatistics st = new DoubleSummaryStatistics();
        forEach(st);
        return st;
    }

    private boolean match(DoublePredicate predicate, boolean decisive, boolean ifDecided) {
        Objects.requireNonNull(predicate);
        Ends.Match m = new Ends.Match(decisive, ifDecided) {
            @Override
            public void accept(double x) { see(predicate.test(x)); }
        };
        run(m, true);
        return m.answer();
    }

    @Override
    public boolean anyMatch(DoublePredicate predicate) { return match(predicate, true, true); }

    @Override
    public boolean allMatch(DoublePredicate predicate) { return match(predicate, false, false); }

    @Override
    public boolean noneMatch(DoublePredicate predicate) { return match(predicate, true, false); }

    @Override
    public OptionalDouble findFirst() {
        Ends.First f = new Ends.First();
        run(f, true);
        return f.found ? OptionalDouble.of((Double) f.value) : OptionalDouble.empty();
    }

    @Override
    public OptionalDouble findAny() { return findFirst(); }

    @Override
    public Stream<Double> boxed() {
        return new RefFlow<Double>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(double x) { next.accept(Double.valueOf(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public PrimitiveIterator.OfDouble iterator() { return Spliterators.iterator(spliterator()); }

    @Override
    public Spliterator.OfDouble spliterator() { return (Spliterator.OfDouble) spliteratorOf(); }

    @Override
    Spliterator<?> stepper() { return new Steps.Doubles(this); }
}
