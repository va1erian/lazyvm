package java.util.stream;

import static java.util.stream.Collector.Characteristics.IDENTITY_FINISH;
import static java.util.stream.Collector.Characteristics.UNORDERED;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

public final class Collectors {
    private Collectors() {
    }

    // ---- into collections ----

    public static <T, C extends Collection<T>> Collector<T, ?, C> toCollection(Supplier<C> collectionFactory) {
        return Parts.direct(collectionFactory, (c, t) -> c.add(t), (a, b) -> {
            a.addAll(b);
            return a;
        }, Parts.traits(IDENTITY_FINISH));
    }

    public static <T> Collector<T, ?, List<T>> toList() {
        return Parts.direct(() -> new ArrayList<T>(), (l, t) -> l.add(t), (a, b) -> {
            a.addAll(b);
            return a;
        }, Parts.traits(IDENTITY_FINISH));
    }

    public static <T> Collector<T, ?, Set<T>> toSet() {
        return Parts.direct(() -> new HashSet<T>(), (s, t) -> s.add(t), (a, b) -> {
            a.addAll(b);
            return a;
        }, Parts.traits(UNORDERED, IDENTITY_FINISH));
    }

    // ---- text ----

    public static Collector<CharSequence, ?, String> joining() {
        return new Parts<CharSequence, StringBuilder, String>(() -> new StringBuilder(), (sb, s) -> sb.append(s),
                (a, b) -> a.append(b), sb -> sb.toString(), Parts.traits());
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
        return joining(delimiter, "", "");
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter, CharSequence prefix,
            CharSequence suffix) {
        return new Parts<CharSequence, StringJoiner, String>(() -> new StringJoiner(delimiter, prefix, suffix),
                (j, s) -> j.add(s), (a, b) -> a.merge(b), j -> j.toString(), Parts.traits());
    }

    // ---- wrapping another collector ----

    public static <T, U, A, R> Collector<T, ?, R> mapping(Function<? super T, ? extends U> mapper,
            Collector<? super U, A, R> downstream) {
        BiConsumer<A, ? super U> addTo = downstream.accumulator();
        return new Parts<T, A, R>(downstream.supplier(), (a, t) -> addTo.accept(a, mapper.apply(t)),
                downstream.combiner(), downstream.finisher(), downstream.characteristics());
    }

    public static <T, A, R, RR> Collector<T, A, RR> collectingAndThen(Collector<T, A, R> downstream,
            Function<R, RR> finisher) {
        Set<Collector.Characteristics> traits = downstream.characteristics();
        Supplier<A> supplier = downstream.supplier();
        BiConsumer<A, T> accumulator = downstream.accumulator();
        BinaryOperator<A> combiner = downstream.combiner();
        Function<A, RR> then = downstream.finisher().andThen(finisher);
        EnumSet<Collector.Characteristics> rest = EnumSet.noneOf(Collector.Characteristics.class);
        rest.addAll(traits);
        rest.remove(IDENTITY_FINISH);
        return new Parts<T, A, RR>(supplier, accumulator, combiner, then, java.util.Collections.unmodifiableSet(rest));
    }

    // ---- counting and reducing ----

    public static <T> Collector<T, ?, Long> counting() {
        return new Parts<T, long[], Long>(() -> new long[1], (a, t) -> a[0]++, (a, b) -> {
            a[0] += b[0];
            return a;
        }, a -> a[0], Parts.traits());
    }

    public static <T> Collector<T, ?, Optional<T>> minBy(Comparator<? super T> comparator) {
        return reducing(BinaryOperator.minBy(comparator));
    }

    public static <T> Collector<T, ?, Optional<T>> maxBy(Comparator<? super T> comparator) {
        return reducing(BinaryOperator.maxBy(comparator));
    }

    public static <T> Collector<T, ?, Optional<T>> reducing(BinaryOperator<T> op) {
        return new Parts<T, Held<T>, Optional<T>>(() -> new Held<T>(), (h, t) -> h.put(t, op),
                (a, b) -> b.seen ? a.put(b.value, op) : a, h -> Optional.ofNullable(h.value), Parts.traits());
    }

    @SuppressWarnings("unchecked")
    public static <T> Collector<T, ?, T> reducing(T identity, BinaryOperator<T> op) {
        return new Parts<T, Object[], T>(() -> new Object[] {identity}, (a, t) -> a[0] = op.apply((T) a[0], t),
                (a, b) -> {
                    a[0] = op.apply((T) a[0], (T) b[0]);
                    return a;
                }, a -> (T) a[0], Parts.traits());
    }

    @SuppressWarnings("unchecked")
    public static <T, U> Collector<T, ?, U> reducing(U identity, Function<? super T, ? extends U> mapper,
            BinaryOperator<U> op) {
        return new Parts<T, Object[], U>(() -> new Object[] {identity},
                (a, t) -> a[0] = op.apply((U) a[0], mapper.apply(t)), (a, b) -> {
                    a[0] = op.apply((U) a[0], (U) b[0]);
                    return a;
                }, a -> (U) a[0], Parts.traits());
    }

    /** The running result of reducing(op): nothing until the first element arrives. */
    private static final class Held<T> {
        boolean seen;
        T value;

        Held<T> put(T t, BinaryOperator<T> op) {
            value = seen ? op.apply(value, t) : t;
            seen = true;
            return this;
        }
    }

    // ---- sums, averages, statistics ----

    public static <T> Collector<T, ?, Integer> summingInt(ToIntFunction<? super T> mapper) {
        return new Parts<T, int[], Integer>(() -> new int[1], (a, t) -> a[0] += mapper.applyAsInt(t), (a, b) -> {
            a[0] += b[0];
            return a;
        }, a -> a[0], Parts.traits());
    }

    public static <T> Collector<T, ?, Long> summingLong(ToLongFunction<? super T> mapper) {
        return new Parts<T, long[], Long>(() -> new long[1], (a, t) -> a[0] += mapper.applyAsLong(t), (a, b) -> {
            a[0] += b[0];
            return a;
        }, a -> a[0], Parts.traits());
    }

    public static <T> Collector<T, ?, Double> summingDouble(ToDoubleFunction<? super T> mapper) {
        return new Parts<T, DoubleSummaryStatistics, Double>(() -> new DoubleSummaryStatistics(),
                (s, t) -> s.accept(mapper.applyAsDouble(t)), (a, b) -> {
                    a.combine(b);
                    return a;
                }, s -> s.getSum(), Parts.traits());
    }

    public static <T> Collector<T, ?, Double> averagingInt(ToIntFunction<? super T> mapper) {
        return averaging((a, t) -> a[0] += mapper.applyAsInt(t));
    }

    public static <T> Collector<T, ?, Double> averagingLong(ToLongFunction<? super T> mapper) {
        return averaging((a, t) -> a[0] += mapper.applyAsLong(t));
    }

    /** The mean of integral values: a sum and a count that `addValue` fills in a two-slot array. */
    private static <T> Collector<T, ?, Double> averaging(BiConsumer<long[], T> addValue) {
        return new Parts<T, long[], Double>(() -> new long[2], (a, t) -> {
            addValue.accept(a, t);
            a[1]++;
        }, (a, b) -> {
            a[0] += b[0];
            a[1] += b[1];
            return a;
        }, a -> a[1] == 0 ? 0.0d : (double) a[0] / a[1], Parts.traits());
    }

    public static <T> Collector<T, ?, Double> averagingDouble(ToDoubleFunction<? super T> mapper) {
        return new Parts<T, DoubleSummaryStatistics, Double>(() -> new DoubleSummaryStatistics(),
                (s, t) -> s.accept(mapper.applyAsDouble(t)), (a, b) -> {
                    a.combine(b);
                    return a;
                }, s -> s.getAverage(), Parts.traits());
    }

    public static <T> Collector<T, ?, IntSummaryStatistics> summarizingInt(ToIntFunction<? super T> mapper) {
        return Parts.direct(() -> new IntSummaryStatistics(), (s, t) -> s.accept(mapper.applyAsInt(t)), (a, b) -> {
            a.combine(b);
            return a;
        }, Parts.traits(IDENTITY_FINISH));
    }

    public static <T> Collector<T, ?, LongSummaryStatistics> summarizingLong(ToLongFunction<? super T> mapper) {
        return Parts.direct(() -> new LongSummaryStatistics(), (s, t) -> s.accept(mapper.applyAsLong(t)), (a, b) -> {
            a.combine(b);
            return a;
        }, Parts.traits(IDENTITY_FINISH));
    }

    public static <T> Collector<T, ?, DoubleSummaryStatistics> summarizingDouble(
            ToDoubleFunction<? super T> mapper) {
        return Parts.direct(() -> new DoubleSummaryStatistics(), (s, t) -> s.accept(mapper.applyAsDouble(t)),
                (a, b) -> {
                    a.combine(b);
                    return a;
                }, Parts.traits(IDENTITY_FINISH));
    }

    // ---- grouping and partitioning ----

    public static <T, K> Collector<T, ?, Map<K, List<T>>> groupingBy(Function<? super T, ? extends K> classifier) {
        return groupingBy(classifier, toList());
    }

    public static <T, K, A, D> Collector<T, ?, Map<K, D>> groupingBy(Function<? super T, ? extends K> classifier,
            Collector<? super T, A, D> downstream) {
        return groupingBy(classifier, () -> new HashMap<K, D>(), downstream);
    }

    public static <T, K, D, A, M extends Map<K, D>> Collector<T, ?, M> groupingBy(
            Function<? super T, ? extends K> classifier, Supplier<M> mapFactory,
            Collector<? super T, A, D> downstream) {
        return Groupers.group(classifier, mapFactory, downstream, false);
    }

    public static <T, K> Collector<T, ?, ConcurrentMap<K, List<T>>> groupingByConcurrent(
            Function<? super T, ? extends K> classifier) {
        return groupingByConcurrent(classifier, () -> new ConcurrentHashMap<K, List<T>>(), toList());
    }

    public static <T, K, A, D> Collector<T, ?, ConcurrentMap<K, D>> groupingByConcurrent(
            Function<? super T, ? extends K> classifier, Collector<? super T, A, D> downstream) {
        return groupingByConcurrent(classifier, () -> new ConcurrentHashMap<K, D>(), downstream);
    }

    public static <T, K, A, D, M extends ConcurrentMap<K, D>> Collector<T, ?, M> groupingByConcurrent(
            Function<? super T, ? extends K> classifier, Supplier<M> mapFactory,
            Collector<? super T, A, D> downstream) {
        return Groupers.group(classifier, mapFactory, downstream, true);
    }

    public static <T> Collector<T, ?, Map<Boolean, List<T>>> partitioningBy(Predicate<? super T> predicate) {
        return partitioningBy(predicate, toList());
    }

    public static <T, D, A> Collector<T, ?, Map<Boolean, D>> partitioningBy(Predicate<? super T> predicate,
            Collector<? super T, A, D> downstream) {
        return Groupers.partition(predicate, downstream);
    }

    // ---- into maps ----

    public static <T, K, U> Collector<T, ?, Map<K, U>> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper) {
        return toMap(keyMapper, valueMapper, Groupers.<U>noRepeats(), () -> new HashMap<K, U>());
    }

    public static <T, K, U> Collector<T, ?, Map<K, U>> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends U> valueMapper, BinaryOperator<U> mergeFunction) {
        return toMap(keyMapper, valueMapper, mergeFunction, () -> new HashMap<K, U>());
    }

    public static <T, K, U, M extends Map<K, U>> Collector<T, ?, M> toMap(
            Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper,
            BinaryOperator<U> mergeFunction, Supplier<M> mapSupplier) {
        return Groupers.toMap(keyMapper, valueMapper, mergeFunction, mapSupplier, Parts.traits(IDENTITY_FINISH));
    }

    public static <T, K, U> Collector<T, ?, ConcurrentMap<K, U>> toConcurrentMap(
            Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper) {
        return toConcurrentMap(keyMapper, valueMapper, Groupers.<U>noRepeats(), () -> new ConcurrentHashMap<K, U>());
    }

    public static <T, K, U> Collector<T, ?, ConcurrentMap<K, U>> toConcurrentMap(
            Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper,
            BinaryOperator<U> mergeFunction) {
        return toConcurrentMap(keyMapper, valueMapper, mergeFunction, () -> new ConcurrentHashMap<K, U>());
    }

    public static <T, K, U, M extends ConcurrentMap<K, U>> Collector<T, ?, M> toConcurrentMap(
            Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper,
            BinaryOperator<U> mergeFunction, Supplier<M> mapSupplier) {
        return Groupers.toMap(keyMapper, valueMapper, mergeFunction, mapSupplier,
                Parts.traits(Collector.Characteristics.CONCURRENT, UNORDERED, IDENTITY_FINISH));
    }
}
