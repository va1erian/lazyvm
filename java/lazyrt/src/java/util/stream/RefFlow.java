package java.util.stream;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** Stream<T>. */
final class RefFlow<T> extends Flow<T, Stream<T>> implements Stream<T> {
    private static final Comparator<Object> NATURAL = (a, b) -> ((Comparable<Object>) a).compareTo(b);
    private static final int REKEY = Spliterator.DISTINCT | Spliterator.SORTED;

    RefFlow(Pipe pipe, int flags, boolean natural) { super(pipe, flags, natural); }

    RefFlow(Flow<?, ?> from, Relay.Layer layer, int flags, boolean natural, boolean stops) {
        super(from, layer, flags, natural, stops);
    }

    @Override
    Stream<T> stage(Relay.Layer layer, int fl, boolean nat, boolean stops) {
        return new RefFlow<T>(this, layer, fl, nat, stops);
    }

    @Override
    Relay.Layer sorting(boolean exact) { return next -> new Sorting.Refs(next, NATURAL, exact); }

    @Override
    public Stream<T> filter(Predicate<? super T> predicate) {
        Objects.requireNonNull(predicate);
        return stage(next -> new Relay.Narrow(next) {
            @Override
            public void accept(Object x) {
                if (predicate.test(cast(x))) {
                    next.accept(x);
                }
            }
        }, keep(SIZES), natural, false);
    }

    @Override
    public <R> Stream<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper);
        return new RefFlow<R>(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(Object x) { next.accept(mapper.apply(cast(x))); }
        }, keep(REKEY), false, false);
    }

    @Override
    public IntStream mapToInt(ToIntFunction<? super T> mapper) {
        Objects.requireNonNull(mapper);
        return new IntFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(Object x) { next.accept(mapper.applyAsInt(cast(x))); }
        }, keep(REKEY), false, false);
    }

    @Override
    public LongStream mapToLong(ToLongFunction<? super T> mapper) {
        Objects.requireNonNull(mapper);
        return new LongFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(Object x) { next.accept(mapper.applyAsLong(cast(x))); }
        }, keep(REKEY), false, false);
    }

    @Override
    public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
        Objects.requireNonNull(mapper);
        return new DoubleFlow(this, next -> new Relay.Pass(next) {
            @Override
            public void accept(Object x) { next.accept(mapper.applyAsDouble(cast(x))); }
        }, keep(REKEY), false, false);
    }

    private int flatDrop() { return SIZES | REKEY; }

    @Override
    public <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
        Objects.requireNonNull(mapper);
        return new RefFlow<R>(this, next -> new Stages.Flat(next, x -> mapper.apply(cast(x))), keep(flatDrop()),
                false, false);
    }

    @Override
    public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
        Objects.requireNonNull(mapper);
        return new IntFlow(this, next -> new Stages.Flat(next, x -> mapper.apply(cast(x))), keep(flatDrop()),
                false, false);
    }

    @Override
    public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
        Objects.requireNonNull(mapper);
        return new LongFlow(this, next -> new Stages.Flat(next, x -> mapper.apply(cast(x))), keep(flatDrop()),
                false, false);
    }

    @Override
    public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
        Objects.requireNonNull(mapper);
        return new DoubleFlow(this, next -> new Stages.Flat(next, x -> mapper.apply(cast(x))), keep(flatDrop()),
                false, false);
    }

    /** distinct() does nothing to a stream known to be distinct, compares neighbours in a sorted one. */
    @Override
    public Stream<T> distinct() {
        boolean sorted = natural && (flags & Spliterator.SORTED) != 0;
        Relay.Layer layer = (flags & Spliterator.DISTINCT) != 0 ? Relay.SAME
                : sorted ? next -> new Stages.Neighbors(next) : next -> new Stages.Hashed(next);
        return stage(layer, keep(SIZES) | Spliterator.DISTINCT, natural, false);
    }

    @Override
    public Stream<T> sorted(Comparator<? super T> comparator) {
        Comparator<Object> by = (a, b) -> comparator.compare(cast(a), cast(b));
        // The stage is made first, which fails on a used stream; only then is the comparator looked at.
        Stream<T> stage = stage(next -> new Sorting.Refs(next, by, (flags & Spliterator.SIZED) != 0), (flags | Spliterator.ORDERED)
                & ~Spliterator.SORTED, false, false);
        Objects.requireNonNull(comparator);
        return stage;
    }

    @Override
    public Stream<T> peek(Consumer<? super T> action) {
        Objects.requireNonNull(action);
        return stage(next -> new Relay.Pass(next) {
            @Override
            public void accept(Object x) {
                action.accept(cast(x));
                next.accept(x);
            }
        }, keep(0), natural, false);
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        link();
        Objects.requireNonNull(action);
        if (prev == null) {
            ((Spliterator<T>) pipe.source()).forEachRemaining(action);
            return;
        }
        exec(new Relay() {
            @Override
            public void accept(Object x) { action.accept(cast(x)); }
        }, false);
    }

    @Override
    public void forEachOrdered(Consumer<? super T> action) { forEach(action); }

    @Override
    public Object[] toArray() {
        link();
        Ends.Refs<Object> ends = new Ends.Refs<Object>(Object[]::new, announce());
        exec(ends, false);
        return ends.result();
    }

    @Override
    public <A> A[] toArray(IntFunction<A[]> generator) {
        link();
        Objects.requireNonNull(generator);
        Ends.Refs<A> ends = new Ends.Refs<A>(generator, announce());
        exec(ends, false);
        return ends.result();
    }

    @Override
    public T reduce(T identity, BinaryOperator<T> accumulator) {
        Objects.requireNonNull(accumulator);
        Ends.Fold f = new Ends.Fold(identity, cast(accumulator));
        run(f, false);
        return cast(f.acc);
    }

    @Override
    public Optional<T> reduce(BinaryOperator<T> accumulator) {
        Objects.requireNonNull(accumulator);
        Ends.Fold f = new Ends.Fold(cast(accumulator));
        run(f, false);
        return f.any ? Optional.of(cast(f.acc)) : Optional.empty();
    }

    @Override
    public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator, BinaryOperator<U> combiner) {
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        Ends.Fold f = new Ends.Fold(identity, (a, x) -> accumulator.apply(cast(a), cast(x)));
        run(f, false);
        return cast(f.acc);
    }

    @Override
    public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator, BiConsumer<R, R> combiner) {
        Objects.requireNonNull(supplier);
        Objects.requireNonNull(accumulator);
        Objects.requireNonNull(combiner);
        link();
        R r = supplier.get();
        exec(new Relay() {
            @Override
            public void accept(Object x) { accumulator.accept(r, cast(x)); }
        }, false);
        return r;
    }

    @Override
    public <R, A> R collect(Collector<? super T, A, R> collector) {
        Objects.requireNonNull(collector);
        link();
        Supplier<A> supplier = collector.supplier();
        BiConsumer<A, ? super T> accumulator = collector.accumulator();
        collector.combiner();
        collector.characteristics();
        A container = supplier.get();
        exec(new Relay() {
            @Override
            public void accept(Object x) { accumulator.accept(container, cast(x)); }
        }, false);
        return collector.characteristics().contains(Collector.Characteristics.IDENTITY_FINISH)
                ? cast(container) : collector.finisher().apply(container);
    }

    @Override
    public Optional<T> min(Comparator<? super T> comparator) { return reduce(BinaryOperator.minBy(comparator)); }

    @Override
    public Optional<T> max(Comparator<? super T> comparator) { return reduce(BinaryOperator.maxBy(comparator)); }

    @Override
    public long count() {
        Ends.Count c = new Ends.Count();
        run(c, false);
        return c.n;
    }

    private boolean match(Predicate<? super T> predicate, boolean decisive, boolean ifDecided) {
        Objects.requireNonNull(predicate);
        Ends.Match m = new Ends.Match(decisive, ifDecided) {
            @Override
            public void accept(Object x) { see(predicate.test(cast(x))); }
        };
        run(m, true);
        return m.answer();
    }

    @Override
    public boolean anyMatch(Predicate<? super T> predicate) { return match(predicate, true, true); }

    @Override
    public boolean allMatch(Predicate<? super T> predicate) { return match(predicate, false, false); }

    @Override
    public boolean noneMatch(Predicate<? super T> predicate) { return match(predicate, true, false); }

    @Override
    public Optional<T> findFirst() {
        Ends.First f = new Ends.First();
        run(f, true);
        return f.found ? Optional.of(cast(f.value)) : Optional.empty();
    }

    @Override
    public Optional<T> findAny() { return findFirst(); }

    @Override
    public Iterator<T> iterator() { return Spliterators.iterator(spliterator()); }

    @Override
    public Spliterator<T> spliterator() { return cast(spliteratorOf()); }

    @Override
    Spliterator<?> stepper() { return new Steps.Refs<T>(this); }
}
