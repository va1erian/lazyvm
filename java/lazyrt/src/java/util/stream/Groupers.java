package java.util.stream;

import static java.util.stream.Collector.Characteristics.CONCURRENT;
import static java.util.stream.Collector.Characteristics.IDENTITY_FINISH;
import static java.util.stream.Collector.Characteristics.UNORDERED;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The collectors that build maps: grouping, partitioning and toMap (docs/design/streams.md K2, K4, K5, K7). */
final class Groupers {
    private Groupers() {
    }

    /**
     * Groups by a classifier into the map `mapFactory` makes. The downstream's getters are read in a fixed order
     * (supplier, accumulator, combiner, characteristics, finisher unless it is the identity), and one container
     * per key is made when the key's first element arrives.
     */
    @SuppressWarnings("unchecked")
    static <T, K, D, A, M extends Map<K, D>> Collector<T, ?, M> group(Function<? super T, ? extends K> classifier,
            Supplier<? extends Map<K, ?>> mapFactory, Collector<? super T, A, D> downstream, boolean concurrent) {
        Supplier<A> makeValue = downstream.supplier();
        BiConsumer<A, ? super T> addTo = downstream.accumulator();
        BinaryOperator<A> join = downstream.combiner();
        boolean direct = downstream.characteristics().contains(IDENTITY_FINISH);
        if (concurrent) {
            downstream.characteristics();
        }
        BiConsumer<Map<K, A>, T> accumulator = (m, t) -> {
            K key = Objects.requireNonNull(classifier.apply(t), "element cannot be mapped to a null key");
            addTo.accept(m.computeIfAbsent(key, k -> makeValue.get()), t);
        };
        BinaryOperator<Map<K, A>> combiner = (m1, m2) -> {
            for (Map.Entry<K, A> e : m2.entrySet()) {
                m1.merge(e.getKey(), e.getValue(), join);
            }
            return m1;
        };
        Supplier<Map<K, A>> supplier = (Supplier<Map<K, A>>) (Supplier<?>) mapFactory;
        if (direct) {
            return new Parts<T, Map<K, A>, M>(supplier, accumulator, combiner, m -> (M) m, groupTraits(concurrent, true));
        }
        Function<A, D> finish = downstream.finisher();
        return new Parts<T, Map<K, A>, M>(supplier, accumulator, combiner, m -> {
            ((Map<K, Object>) m).replaceAll((k, v) -> finish.apply((A) v));
            return (M) m;
        }, groupTraits(concurrent, false));
    }

    private static Set<Collector.Characteristics> groupTraits(boolean concurrent, boolean direct) {
        if (concurrent) {
            return direct ? Parts.traits(CONCURRENT, UNORDERED, IDENTITY_FINISH) : Parts.traits(CONCURRENT, UNORDERED);
        }
        return direct ? Parts.traits(IDENTITY_FINISH) : Parts.traits();
    }

    /** The two-entry result of partitioningBy: the keys false and true, in that order, fixed once made. */
    private static <V> Map<Boolean, V> pair(V no, V yes) {
        Map<Boolean, V> m = new LinkedHashMap<>();
        m.put(false, no);
        m.put(true, yes);
        return Collections.unmodifiableMap(m);
    }

    /**
     * Splits by a predicate. The container is already the result map, holding one downstream container per key
     * (`true` made first); without an identity finisher the values are replaced by their finished forms.
     */
    static <T, D, A> Collector<T, ?, Map<Boolean, D>> partition(Predicate<? super T> predicate,
            Collector<? super T, A, D> downstream) {
        BiConsumer<A, ? super T> addTo = downstream.accumulator();
        BinaryOperator<A> join = downstream.combiner();
        Set<Collector.Characteristics> traits = downstream.characteristics();
        Supplier<Map<Boolean, A>> supplier = () -> {
            A yes = downstream.supplier().get();
            A no = downstream.supplier().get();
            return pair(no, yes);
        };
        BiConsumer<Map<Boolean, A>, T> accumulator = (m, t) -> addTo.accept(m.get(predicate.test(t)), t);
        BinaryOperator<Map<Boolean, A>> combiner = (l, r) ->
                pair(join.apply(l.get(false), r.get(false)), join.apply(l.get(true), r.get(true)));
        if (traits.contains(IDENTITY_FINISH)) {
            return new Parts<T, Map<Boolean, A>, Map<Boolean, D>>(supplier, accumulator, combiner,
                    m -> Flow.cast(m), Parts.traits(IDENTITY_FINISH));
        }
        return new Parts<T, Map<Boolean, A>, Map<Boolean, D>>(supplier, accumulator, combiner, m -> {
            D yes = downstream.finisher().apply(m.get(true));
            return pair(downstream.finisher().apply(m.get(false)), yes);
        }, Parts.traits());
    }

    /** Collects into a map built by `mapFactory`; a repeated key goes to `merge` (a null result drops the key). */
    static <T, K, V, M extends Map<K, V>> Collector<T, ?, M> toMap(Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper, BinaryOperator<V> merge, Supplier<M> mapFactory,
            Set<Collector.Characteristics> traits) {
        BiConsumer<M, T> accumulator = (m, t) -> m.merge(keyMapper.apply(t), valueMapper.apply(t), merge);
        BinaryOperator<M> combiner = (m1, m2) -> {
            for (Map.Entry<K, V> e : m2.entrySet()) {
                m1.merge(e.getKey(), e.getValue(), merge);
            }
            return m1;
        };
        return Parts.direct(mapFactory, accumulator, combiner, traits);
    }

    /** The merge function of the plain toMap: a repeated key is an error that shows the value stored first. */
    static <V> BinaryOperator<V> noRepeats() {
        return (first, second) -> {
            throw new IllegalStateException("Duplicate key " + first);
        };
    }
}
