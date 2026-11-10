package java.util.stream;

import java.util.LongSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.LongBinaryOperator;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongToDoubleFunction;
import java.util.function.LongToIntFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.LongToDoubleFunction;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;

/** LongStream. */
final class LongFlow extends Flow<Long, LongStream> implements LongStream {
    private static final int REKEY = Spliterator.DISTINCT | Spliterator.SORTED;

    LongFlow(Pipe pipe, int flags, boolean natural) { super(pipe, flags, natural); }

    LongFlow(Flow<?, ?> from, Relay.Layer layer, int flags, boolean natural, boolean stops) {
        super(from, layer, flags, natural, stops);
    }

    @Override
    LongStream stage(Relay.Layer layer, int fl, boolean nat, boolean stops) {
        return new LongFlow(this, layer, fl, nat, stops);
    }

    @Override
    Relay.Layer sorting(boolean exact) { return next -> new Sorting.Longs(next, exact); }

    @Override
    public LongStream filter(LongPredicate predicate) {
        Objects.requireNonNull(predicate);
        return stage(next -> new Relay.Narrow(next) {
            @Override
            public void accept(long x) {
                if (predicate.test(x)) {
                    next.accept(x);
                }
            }
        }, keep(SIZES), natural, false);
    }

    @Override
    public LongStream map(LongUnaryOperator mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept(mapper.applyAsLong(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public <U> Stream<U> mapToObj(LongFunction<? extends U> mapper) {
        Objects.requireNonNull(mapper);
        return new RefFlow<U>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept(mapper.apply(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public IntStream mapToInt(LongToIntFunction mapper) {
        Objects.requireNonNull(mapper);
        return new IntFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept(mapper.applyAsInt(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public DoubleStream mapToDouble(LongToDoubleFunction mapper) {
        Objects.requireNonNull(mapper);
        return new DoubleFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept(mapper.applyAsDouble(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public LongStream flatMap(LongFunction<? extends LongStream> mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Stages.Flat(next, x -> mapper.apply((Long) x)), keep(SIZES | REKEY), false,
                false);
    }

    @Override
    public LongStream distinct() {
        return stage(next -> new Stages.Hashed(next), keep(SIZES | REKEY), false, false);
    }

    @Override
    public LongStream peek(LongConsumer action) {
        Objects.requireNonNull(action);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) {
                action.accept(x);
                next.accept(x);
            }
        }, keep(0), natural, false);
    }

    @Override
    public void forEach(LongConsumer action) {
        link();
        Objects.requireNonNull(action);
        if (prev == null) {
            ((Spliterator.OfLong) pipe.source()).forEachRemaining(action);
        } else if (action instanceof Relay) {
            exec((Relay) action, false);
        } else {
            exec(new Relay() {
                @Override
                public void accept(long x) { action.accept(x); }
            }, false);
        }
    }

    @Override
    public void forEachOrdered(LongConsumer action) { forEach(action); }

    @Override
    public long[] toArray() {
        link();
        Ends.Longs ends = new Ends.Longs(announce());
        exec(ends, false);
        return ends.result();
    }

    @Override
    public long reduce(long identity, LongBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldLong f = new Ends.FoldLong(identity, op);
        run(f, false);
        return f.acc;
    }

    @Override
    public OptionalLong reduce(LongBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldLong f = new Ends.FoldLong(op);
        run(f, false);
        return f.any ? OptionalLong.of(f.acc) : OptionalLong.empty();
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjLongConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        link();
        R r = supplier.get();
        exec(new Relay() {
            @Override
            public void accept(long x) { accumulator.accept(r, x); }
        }, false);
        return r;
    }

    @Override
    public long sum() {
        Ends.SumLong s = new Ends.SumLong();
        forEach(s);
        return s.total;
    }

    @Override
    public OptionalLong min() { return reduce(Math::min); }

    @Override
    public OptionalLong max() { return reduce(Math::max); }

    @Override
    public long count() {
        Ends.Count c = new Ends.Count();
        run(c, false);
        return c.n;
    }

    @Override
    public OptionalDouble average() {
        Ends.Mean m = new Ends.Mean();
        run(m, false);
        return m.n > 0 ? OptionalDouble.of((double) m.total / m.n) : OptionalDouble.empty();
    }

    @Override
    public LongSummaryStatistics summaryStatistics() {
        LongSummaryStatistics st = new LongSummaryStatistics();
        forEach(st);
        return st;
    }

    private boolean match(LongPredicate predicate, boolean decisive, boolean ifDecided) {
        Objects.requireNonNull(predicate);
        Ends.Match m = new Ends.Match(decisive, ifDecided) {
            @Override
            public void accept(long x) { see(predicate.test(x)); }
        };
        run(m, true);
        return m.answer();
    }

    @Override
    public boolean anyMatch(LongPredicate predicate) { return match(predicate, true, true); }

    @Override
    public boolean allMatch(LongPredicate predicate) { return match(predicate, false, false); }

    @Override
    public boolean noneMatch(LongPredicate predicate) { return match(predicate, true, false); }

    @Override
    public OptionalLong findFirst() {
        Ends.First f = new Ends.First();
        run(f, true);
        return f.found ? OptionalLong.of((Long) f.value) : OptionalLong.empty();
    }

    @Override
    public OptionalLong findAny() { return findFirst(); }

    @Override
    public DoubleStream asDoubleStream() {
        return new DoubleFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept((double) x); }
        }, keep(REKEY), false, false);
    }

    @Override
    public Stream<Long> boxed() {
        return new RefFlow<Long>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(long x) { next.accept(Long.valueOf(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public PrimitiveIterator.OfLong iterator() { return Spliterators.iterator(spliterator()); }

    @Override
    public Spliterator.OfLong spliterator() { return (Spliterator.OfLong) spliteratorOf(); }

    @Override
    Spliterator<?> stepper() { return new Steps.Longs(this); }
}
