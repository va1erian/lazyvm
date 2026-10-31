package java.util.concurrent;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;

/**
 * The public face of a {@link HashCore}. Bulk operations run on the calling thread whatever the
 * threshold: only one thread runs at a time here, so splitting them would gain nothing. They all
 * reduce to the four folds in {@link HashBulk}. Which public methods call which is what a subclass
 * sees, so it follows what was observed (`CollectionSelfCalls`).
 */
public class ConcurrentHashMap<K, V> extends AbstractMap<K, V> implements ConcurrentMap<K, V>, java.io.Serializable {
    final HashCore<K, V> core;
    private final HashBulk<K, V> bulk;
    private KeySetView<K, V> keys;
    private Collection<V> values;
    private Set<Map.Entry<K, V>> entries;

    public ConcurrentHashMap() {
        this(0);
    }

    public ConcurrentHashMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException();
        }
        core = new HashCore<>(initialCapacity);
        bulk = new HashBulk<>(core);
    }

    public ConcurrentHashMap(Map<? extends K, ? extends V> m) {
        this(0);
        putAll(m);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor) {
        this(initialCapacity, loadFactor, 1);
    }

    public ConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
        this(checked(initialCapacity, loadFactor, concurrencyLevel));
    }

    private static int checked(int capacity, float loadFactor, int concurrencyLevel) {
        if (!(loadFactor > 0) || capacity < 0 || concurrencyLevel <= 0) {
            throw new IllegalArgumentException();
        }
        return capacity;
    }

    public int size() {
        return core.size();
    }

    public boolean isEmpty() {
        return core.size() == 0;
    }

    public V get(Object key) {
        return core.get(key);
    }

    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    public boolean containsValue(Object value) {
        return HashViews.holds(valueWalk(), Objects.requireNonNull(value));
    }

    public V put(K key, V value) {
        Objects.requireNonNull(value);
        return core.put(key, value, false);
    }

    public void putAll(Map<? extends K, ? extends V> m) {
        for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
            core.put(e.getKey(), Objects.requireNonNull(e.getValue()), false);
        }
    }

    public V remove(Object key) {
        return core.change(key, null, null);
    }

    public void clear() {
        core.clear();
    }

    public KeySetView<K, V> keySet() {
        if (keys == null) {
            keys = new KeySetView<>(this, null);
        }
        return keys;
    }

    public Collection<V> values() {
        if (values == null) {
            values = new HashViews.Values<>(this);
        }
        return values;
    }

    public Set<Map.Entry<K, V>> entrySet() {
        if (entries == null) {
            entries = new HashViews.Entries<>(this);
        }
        return entries;
    }

    Iterator<K> keyWalk() {
        return new HashViews.KeyWalk<>(core);
    }

    Iterator<V> valueWalk() {
        return new HashViews.ValueWalk<>(core);
    }

    Iterator<Map.Entry<K, V>> entryWalk() {
        return new HashViews.EntryWalk<>(this, core);
    }

    public int hashCode() {
        return HashViews.hashes(entryWalk());
    }

    public String toString() {
        return HashViews.text(this);
    }

    public boolean equals(Object o) {
        return o == this || (o instanceof Map && HashViews.sameMappings(this, (Map<?, ?>) o));
    }

    public V putIfAbsent(K key, V value) {
        Objects.requireNonNull(value);
        return core.put(key, value, true);
    }

    public boolean remove(Object key, Object value) {
        Objects.requireNonNull(key);
        return value != null && core.change(key, null, value) != null;
    }

    public boolean replace(K key, V oldValue, V newValue) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(oldValue);
        Objects.requireNonNull(newValue);
        return core.change(key, newValue, oldValue) != null;
    }

    public V replace(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        return core.change(key, value, null);
    }

    public V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v != null ? v : defaultValue;
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        each(action);
    }

    void each(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        bulk.visit((k, v) -> {
            action.accept(k, v);
            return null;
        });
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        bulk.visit((k, v) -> {
            V seen = v;
            while (seen != null && core.change(k, Objects.requireNonNull(function.apply(k, seen)), seen) == null) {
                seen = core.get(k);
            }
            return null;
        });
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        if (key == null || mappingFunction == null) {
            throw new NullPointerException();
        }
        // Present and no claim of its hash: the common case needs none of its own. A running compute is waited for.
        V v = core.getUnclaimed(key);
        return v != null ? v : core.atomically(key, old -> old != null ? old : mappingFunction.apply(key));
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        return core.atomically(key, old -> old == null ? null : remappingFunction.apply(key, old));
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        if (key == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        return core.atomically(key, old -> remappingFunction.apply(key, old));
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        if (key == null || value == null || remappingFunction == null) {
            throw new NullPointerException();
        }
        return core.atomically(key, old -> old == null ? value : remappingFunction.apply(old, value));
    }

    public boolean contains(Object value) {
        return containsValue(value);
    }

    public Enumeration<K> keys() {
        return new HashViews.KeyWalk<>(core);
    }

    public Enumeration<V> elements() {
        return new HashViews.ValueWalk<>(core);
    }

    public long mappingCount() {
        return core.size();
    }

    public static <K> KeySetView<K, Boolean> newKeySet() {
        return new KeySetView<>(new ConcurrentHashMap<K, Boolean>(), Boolean.TRUE);
    }

    public static <K> KeySetView<K, Boolean> newKeySet(int initialCapacity) {
        return new KeySetView<>(new ConcurrentHashMap<K, Boolean>(initialCapacity), Boolean.TRUE);
    }

    public KeySetView<K, V> keySet(V mappedValue) {
        Objects.requireNonNull(mappedValue);
        return new KeySetView<>(this, mappedValue);
    }

    public void forEach(long parallelismThreshold, BiConsumer<? super K, ? super V> action) {
        each(action);
    }

    public <U> void forEach(long parallelismThreshold, BiFunction<? super K, ? super V, ? extends U> transformer, Consumer<? super U> action) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(action);
        bulk.feed(transformer, action);
    }

    public <U> U search(long parallelismThreshold, BiFunction<? super K, ? super V, ? extends U> searchFunction) {
        Objects.requireNonNull(searchFunction);
        return bulk.visit(searchFunction);
    }

    public <U> U reduce(long parallelismThreshold, BiFunction<? super K, ? super V, ? extends U> transformer, BiFunction<? super U, ? super U, ? extends U> reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.fold(transformer, reducer);
    }

    public double reduceToDouble(long parallelismThreshold, ToDoubleBiFunction<? super K, ? super V> transformer, double basis, DoubleBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldDouble(transformer, basis, reducer);
    }

    public long reduceToLong(long parallelismThreshold, ToLongBiFunction<? super K, ? super V> transformer, long basis, LongBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldLong(transformer, basis, reducer);
    }

    public int reduceToInt(long parallelismThreshold, ToIntBiFunction<? super K, ? super V> transformer, int basis, IntBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldInt(transformer, basis, reducer);
    }

    public void forEachKey(long parallelismThreshold, Consumer<? super K> action) {
        Objects.requireNonNull(action);
        each((k, v) -> action.accept(k));
    }

    public <U> void forEachKey(long parallelismThreshold, Function<? super K, ? extends U> transformer, Consumer<? super U> action) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(action);
        bulk.feed((k, v) -> transformer.apply(k), action);
    }

    public <U> U searchKeys(long parallelismThreshold, Function<? super K, ? extends U> searchFunction) {
        Objects.requireNonNull(searchFunction);
        return bulk.visit((k, v) -> searchFunction.apply(k));
    }

    public K reduceKeys(long parallelismThreshold, BiFunction<? super K, ? super K, ? extends K> reducer) {
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> k, reducer);
    }

    public <U> U reduceKeys(long parallelismThreshold, Function<? super K, ? extends U> transformer, BiFunction<? super U, ? super U, ? extends U> reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> transformer.apply(k), reducer);
    }

    public double reduceKeysToDouble(long parallelismThreshold, ToDoubleFunction<? super K> transformer, double basis, DoubleBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldDouble((k, v) -> transformer.applyAsDouble(k), basis, reducer);
    }

    public long reduceKeysToLong(long parallelismThreshold, ToLongFunction<? super K> transformer, long basis, LongBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldLong((k, v) -> transformer.applyAsLong(k), basis, reducer);
    }

    public int reduceKeysToInt(long parallelismThreshold, ToIntFunction<? super K> transformer, int basis, IntBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldInt((k, v) -> transformer.applyAsInt(k), basis, reducer);
    }

    public void forEachValue(long parallelismThreshold, Consumer<? super V> action) {
        Objects.requireNonNull(action);
        each((k, v) -> action.accept(v));
    }

    public <U> void forEachValue(long parallelismThreshold, Function<? super V, ? extends U> transformer, Consumer<? super U> action) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(action);
        bulk.feed((k, v) -> transformer.apply(v), action);
    }

    public <U> U searchValues(long parallelismThreshold, Function<? super V, ? extends U> searchFunction) {
        Objects.requireNonNull(searchFunction);
        return bulk.visit((k, v) -> searchFunction.apply(v));
    }

    public V reduceValues(long parallelismThreshold, BiFunction<? super V, ? super V, ? extends V> reducer) {
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> v, reducer);
    }

    public <U> U reduceValues(long parallelismThreshold, Function<? super V, ? extends U> transformer, BiFunction<? super U, ? super U, ? extends U> reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> transformer.apply(v), reducer);
    }

    public double reduceValuesToDouble(long parallelismThreshold, ToDoubleFunction<? super V> transformer, double basis, DoubleBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldDouble((k, v) -> transformer.applyAsDouble(v), basis, reducer);
    }

    public long reduceValuesToLong(long parallelismThreshold, ToLongFunction<? super V> transformer, long basis, LongBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldLong((k, v) -> transformer.applyAsLong(v), basis, reducer);
    }

    public int reduceValuesToInt(long parallelismThreshold, ToIntFunction<? super V> transformer, int basis, IntBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldInt((k, v) -> transformer.applyAsInt(v), basis, reducer);
    }

    public void forEachEntry(long parallelismThreshold, Consumer<? super Map.Entry<K, V>> action) {
        Objects.requireNonNull(action);
        each((k, v) -> action.accept(bulk.entry(k, v)));
    }

    public <U> void forEachEntry(long parallelismThreshold, Function<Map.Entry<K, V>, ? extends U> transformer, Consumer<? super U> action) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(action);
        bulk.feed((k, v) -> transformer.apply(bulk.entry(k, v)), action);
    }

    public <U> U searchEntries(long parallelismThreshold, Function<Map.Entry<K, V>, ? extends U> searchFunction) {
        Objects.requireNonNull(searchFunction);
        return bulk.visit((k, v) -> searchFunction.apply(bulk.entry(k, v)));
    }

    public Map.Entry<K, V> reduceEntries(long parallelismThreshold, BiFunction<Map.Entry<K, V>, Map.Entry<K, V>, ? extends Map.Entry<K, V>> reducer) {
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> bulk.entry(k, v), reducer);
    }

    public <U> U reduceEntries(long parallelismThreshold, Function<Map.Entry<K, V>, ? extends U> transformer, BiFunction<? super U, ? super U, ? extends U> reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.fold((k, v) -> transformer.apply(bulk.entry(k, v)), reducer);
    }

    public double reduceEntriesToDouble(long parallelismThreshold, ToDoubleFunction<Map.Entry<K, V>> transformer, double basis, DoubleBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldDouble((k, v) -> transformer.applyAsDouble(bulk.entry(k, v)), basis, reducer);
    }

    public long reduceEntriesToLong(long parallelismThreshold, ToLongFunction<Map.Entry<K, V>> transformer, long basis, LongBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldLong((k, v) -> transformer.applyAsLong(bulk.entry(k, v)), basis, reducer);
    }

    public int reduceEntriesToInt(long parallelismThreshold, ToIntFunction<Map.Entry<K, V>> transformer, int basis, IntBinaryOperator reducer) {
        Objects.requireNonNull(transformer);
        Objects.requireNonNull(reducer);
        return bulk.foldInt((k, v) -> transformer.applyAsInt(bulk.entry(k, v)), basis, reducer);
    }

    /** With a mapped value, {@code add} puts it; without one, {@code add} is refused. */
    public static class KeySetView<K, V> extends HashViews<K, V, K> implements Set<K>, java.io.Serializable {
        private final V value;

        KeySetView(ConcurrentHashMap<K, V> map, V value) {
            super(map);
            this.value = value;
        }

        public V getMappedValue() {
            return value;
        }

        public boolean contains(Object o) {
            return map.containsKey(o);
        }

        public boolean remove(Object o) {
            return map.remove(o) != null;
        }

        public Iterator<K> iterator() {
            return map.keyWalk();
        }

        public boolean add(K e) {
            if (value == null) {
                throw new UnsupportedOperationException();
            }
            return map.core.put(e, value, true) == null;
        }

        public boolean addAll(Collection<? extends K> c) {
            if (value == null) {
                throw new UnsupportedOperationException();
            }
            boolean grew = false;
            for (K e : c) {
                grew |= add(e);
            }
            return grew;
        }

        public int hashCode() {
            return elementHashes();
        }

        public boolean equals(Object o) {
            return sameElements(o);
        }

        public void forEach(Consumer<? super K> action) {
            Objects.requireNonNull(action);
            map.each((k, v) -> action.accept(k));
        }

        public ConcurrentHashMap<K, V> getMap() {
            return map;
        }
    }
}
