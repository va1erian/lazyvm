package java.util.stream;

import java.util.IntSummaryStatistics;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.IntBinaryOperator;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.ObjIntConsumer;
import java.util.function.Supplier;

/** IntStream. */
final class IntFlow extends Flow<Integer, IntStream> implements IntStream {
    private static final int REKEY = Spliterator.DISTINCT | Spliterator.SORTED;

    IntFlow(Pipe pipe, int flags, boolean natural) { super(pipe, flags, natural); }

    IntFlow(Flow<?, ?> from, Relay.Layer layer, int flags, boolean natural, boolean stops) {
        super(from, layer, flags, natural, stops);
    }

    @Override
    IntStream stage(Relay.Layer layer, int fl, boolean nat, boolean stops) {
        return new IntFlow(this, layer, fl, nat, stops);
    }

    @Override
    Relay.Layer sorting() { return next -> new Sorting.Ints(next); }

    @Override
    public IntStream filter(IntPredicate predicate) {
        Objects.requireNonNull(predicate);
        return stage(next -> new Relay.Narrow(next) {
            @Override
            public void accept(int x) {
                if (predicate.test(x)) {
                    next.accept(x);
                }
            }
        }, keep(SIZES), natural, false);
    }

    @Override
    public IntStream map(IntUnaryOperator mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept(mapper.applyAsInt(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public <U> Stream<U> mapToObj(IntFunction<? extends U> mapper) {
        Objects.requireNonNull(mapper);
        return new RefFlow<U>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept(mapper.apply(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public LongStream mapToLong(IntToLongFunction mapper) {
        Objects.requireNonNull(mapper);
        return new LongFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept(mapper.applyAsLong(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public DoubleStream mapToDouble(IntToDoubleFunction mapper) {
        Objects.requireNonNull(mapper);
        return new DoubleFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept(mapper.applyAsDouble(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public IntStream flatMap(IntFunction<? extends IntStream> mapper) {
        Objects.requireNonNull(mapper);
        return stage(next -> new Stages.Flat(next, x -> mapper.apply((Integer) x)), keep(SIZES | REKEY), false,
                false);
    }

    @Override
    public IntStream distinct() {
        return stage(next -> new Stages.Hashed(next), keep(SIZES | REKEY), false, false);
    }

    @Override
    public IntStream peek(IntConsumer action) {
        Objects.requireNonNull(action);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) {
                action.accept(x);
                next.accept(x);
            }
        }, keep(0), natural, false);
    }

    @Override
    public void forEach(IntConsumer action) {
        link();
        Objects.requireNonNull(action);
        if (prev == null) {
            ((Spliterator.OfInt) pipe.source()).forEachRemaining(action);
        } else if (action instanceof Relay) {
            exec((Relay) action, false);
        } else {
            exec(new Relay() {
                @Override
                public void accept(int x) { action.accept(x); }
            }, false);
        }
    }

    @Override
    public void forEachOrdered(IntConsumer action) { forEach(action); }

    @Override
    public int[] toArray() {
        link();
        Ends.Ints ends = new Ends.Ints(announce());
        exec(ends, false);
        return ends.result();
    }

    @Override
    public int reduce(int identity, IntBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldInt f = new Ends.FoldInt(identity, op);
        run(f, false);
        return f.acc;
    }

    @Override
    public OptionalInt reduce(IntBinaryOperator op) {
        Objects.requireNonNull(op);
        Ends.FoldInt f = new Ends.FoldInt(op);
        run(f, false);
        return f.any ? OptionalInt.of(f.acc) : OptionalInt.empty();
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        link();
        R r = supplier.get();
        exec(new Relay() {
            @Override
            public void accept(int x) { accumulator.accept(r, x); }
        }, false);
        return r;
    }

    @Override
    public int sum() {
        Ends.SumInt s = new Ends.SumInt();
        forEach(s);
        return s.total;
    }

    @Override
    public OptionalInt min() { return reduce(Math::min); }

    @Override
    public OptionalInt max() { return reduce(Math::max); }

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
    public IntSummaryStatistics summaryStatistics() {
        IntSummaryStatistics st = new IntSummaryStatistics();
        forEach(st);
        return st;
    }

    private boolean match(IntPredicate predicate, boolean decisive, boolean ifDecided) {
        Objects.requireNonNull(predicate);
        Ends.Match m = new Ends.Match(decisive, ifDecided) {
            @Override
            public void accept(int x) { see(predicate.test(x)); }
        };
        run(m, true);
        return m.answer();
    }

    @Override
    public boolean anyMatch(IntPredicate predicate) { return match(predicate, true, true); }

    @Override
    public boolean allMatch(IntPredicate predicate) { return match(predicate, false, false); }

    @Override
    public boolean noneMatch(IntPredicate predicate) { return match(predicate, true, false); }

    @Override
    public OptionalInt findFirst() {
        Ends.First f = new Ends.First();
        run(f, true);
        return f.found ? OptionalInt.of((Integer) f.value) : OptionalInt.empty();
    }

    @Override
    public OptionalInt findAny() { return findFirst(); }

    @Override
    public LongStream asLongStream() {
        return new LongFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept((long) x); }
        }, keep(REKEY), false, false);
    }

    @Override
    public DoubleStream asDoubleStream() {
        return new DoubleFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept((double) x); }
        }, keep(REKEY), false, false);
    }

    @Override
    public Stream<Integer> boxed() {
        return new RefFlow<Integer>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(int x) { next.accept(Integer.valueOf(x)); }
        }, keep(REKEY), false, false);
    }

    @Override
    public PrimitiveIterator.OfInt iterator() { return Spliterators.iterator(spliterator()); }

    @Override
    public Spliterator.OfInt spliterator() { return (Spliterator.OfInt) spliteratorOf(); }

    @Override
    Spliterator<?> stepper() { return new Steps.Ints(this); }
}
