package java.util.concurrent;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Not a skip list: one balanced tree under one monitor gives the same contract in fewer bytes, and
 * only one thread runs at a time here anyway. {@code size()} is constant time, which beats the
 * Javadoc. Stepping an iterator is a tree search, logarithmic where the Javadoc implies constant.
 * Every view, including the whole map's own, is a {@link SkipView}. Computing functions run with no
 * lock, through the retrying defaults of {@link ConcurrentMap}: the Javadoc does not promise once.
 */
public class ConcurrentSkipListMap<K, V> extends AbstractMap<K, V>
        implements ConcurrentNavigableMap<K, V>, Cloneable, java.io.Serializable {
    /** Replaced only by {@code clone}, on a copy nobody else has yet. */
    private TreeMap<K, V> tree;
    private SkipView<K, V> whole;

    public ConcurrentSkipListMap() {
        this((Comparator<? super K>) null);
    }

    public ConcurrentSkipListMap(Comparator<? super K> comparator) {
        tree = new TreeMap<>(comparator);
        whole = new SkipView<>(this);
    }

    public ConcurrentSkipListMap(Map<? extends K, ? extends V> m) {
        this();
        putAll(m);
    }

    public ConcurrentSkipListMap(SortedMap<K, ? extends V> m) {
        this(m.comparator());
        putAll(m);
    }

    @SuppressWarnings("unchecked")
    public ConcurrentSkipListMap<K, V> clone() {
        try {
            ConcurrentSkipListMap<K, V> copy = (ConcurrentSkipListMap<K, V>) super.clone();
            synchronized (tree) {
                copy.tree = new TreeMap<>((SortedMap<K, V>) tree);
            }
            copy.whole = new SkipView<>(copy);
            return copy;
        } catch (CloneNotSupportedException impossible) {
            throw new InternalError();
        }
    }

    private static <T> T need(T argument) {
        return Objects.requireNonNull(argument);
    }

    public boolean containsKey(Object key) {
        need(key);
        synchronized (tree) {
            return tree.containsKey(key);
        }
    }

    public V get(Object key) {
        need(key);
        synchronized (tree) {
            return tree.get(key);
        }
    }

    public V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v != null ? v : defaultValue;
    }

    public V put(K key, V value) {
        need(key);
        need(value);
        synchronized (tree) {
            return tree.put(key, value);
        }
    }

    public V remove(Object key) {
        need(key);
        synchronized (tree) {
            return tree.remove(key);
        }
    }

    public boolean containsValue(Object value) {
        need(value);
        synchronized (tree) {
            return tree.containsValue(value);
        }
    }

    public int size() {
        return tree.size();
    }

    public boolean isEmpty() {
        return tree.isEmpty();
    }

    public void clear() {
        synchronized (tree) {
            tree.clear();
        }
    }

    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        need(key);
        return ConcurrentNavigableMap.super.computeIfAbsent(key, mappingFunction);
    }

    public V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        need(key);
        return ConcurrentNavigableMap.super.computeIfPresent(key, remappingFunction);
    }

    public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        need(key);
        return ConcurrentNavigableMap.super.compute(key, remappingFunction);
    }

    public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        need(key);
        return ConcurrentNavigableMap.super.merge(key, value, remappingFunction);
    }

    public NavigableSet<K> keySet() {
        return whole.keySet();
    }

    public NavigableSet<K> navigableKeySet() {
        return whole.keySet();
    }

    public Collection<V> values() {
        return whole.values();
    }

    public Set<Map.Entry<K, V>> entrySet() {
        return whole.entrySet();
    }

    public ConcurrentNavigableMap<K, V> descendingMap() {
        return whole.descendingMap();
    }

    public NavigableSet<K> descendingKeySet() {
        return whole.descendingKeySet();
    }

    /** Both ways round, with no size comparison: the sizes may be changing. */
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Map)) {
            return false;
        }
        Map<?, ?> m = (Map<?, ?>) o;
        try {
            for (Map.Entry<K, V> e : entrySet()) {
                if (!e.getValue().equals(m.get(e.getKey()))) {
                    return false;
                }
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object k = e.getKey();
                Object v = e.getValue();
                if (k == null || v == null || !v.equals(get(k))) {
                    return false;
                }
            }
            return true;
        } catch (ClassCastException | NullPointerException unlike) {
            return false;
        }
    }

    public V putIfAbsent(K key, V value) {
        need(key);
        need(value);
        synchronized (tree) {
            V old = tree.get(key);
            if (old == null) {
                tree.put(key, value);
            }
            return old;
        }
    }

    public boolean remove(Object key, Object value) {
        need(key);
        if (value == null) {
            return false;
        }
        synchronized (tree) {
            V old = tree.get(key);
            if (old == null || !value.equals(old)) {
                return false;
            }
            tree.remove(key);
            return true;
        }
    }

    public boolean replace(K key, V oldValue, V newValue) {
        need(key);
        need(oldValue);
        need(newValue);
        synchronized (tree) {
            V old = tree.get(key);
            if (old == null || !oldValue.equals(old)) {
                return false;
            }
            tree.put(key, newValue);
            return true;
        }
    }

    public V replace(K key, V value) {
        need(key);
        need(value);
        synchronized (tree) {
            return tree.containsKey(key) ? tree.put(key, value) : null;
        }
    }

    public Comparator<? super K> comparator() {
        return tree.comparator();
    }

    public K firstKey() {
        synchronized (tree) {
            return tree.firstKey();
        }
    }

    public K lastKey() {
        synchronized (tree) {
            return tree.lastKey();
        }
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, boolean fromInclusive, K toKey, boolean toInclusive) {
        return whole.subMap(fromKey, fromInclusive, toKey, toInclusive);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey, boolean inclusive) {
        return whole.headMap(toKey, inclusive);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey, boolean inclusive) {
        return whole.tailMap(fromKey, inclusive);
    }

    public ConcurrentNavigableMap<K, V> subMap(K fromKey, K toKey) {
        return whole.subMap(fromKey, toKey);
    }

    public ConcurrentNavigableMap<K, V> headMap(K toKey) {
        return whole.headMap(toKey);
    }

    public ConcurrentNavigableMap<K, V> tailMap(K fromKey) {
        return whole.tailMap(fromKey);
    }

    public Map.Entry<K, V> lowerEntry(K key) {
        need(key);
        synchronized (tree) {
            return tree.lowerEntry(key);
        }
    }

    public K lowerKey(K key) {
        return keyOf(lowerEntry(key));
    }

    public Map.Entry<K, V> floorEntry(K key) {
        need(key);
        synchronized (tree) {
            return tree.floorEntry(key);
        }
    }

    public K floorKey(K key) {
        return keyOf(floorEntry(key));
    }

    public Map.Entry<K, V> ceilingEntry(K key) {
        need(key);
        synchronized (tree) {
            return tree.ceilingEntry(key);
        }
    }

    public K ceilingKey(K key) {
        return keyOf(ceilingEntry(key));
    }

    public Map.Entry<K, V> higherEntry(K key) {
        need(key);
        synchronized (tree) {
            return tree.higherEntry(key);
        }
    }

    public K higherKey(K key) {
        return keyOf(higherEntry(key));
    }

    static <K, V> K keyOf(Map.Entry<K, V> e) {
        return e == null ? null : e.getKey();
    }

    public Map.Entry<K, V> firstEntry() {
        synchronized (tree) {
            return tree.firstEntry();
        }
    }

    public Map.Entry<K, V> lastEntry() {
        synchronized (tree) {
            return tree.lastEntry();
        }
    }

    public Map.Entry<K, V> pollFirstEntry() {
        synchronized (tree) {
            return tree.pollFirstEntry();
        }
    }

    public Map.Entry<K, V> pollLastEntry() {
        synchronized (tree) {
            return tree.pollLastEntry();
        }
    }

    public void forEach(BiConsumer<? super K, ? super V> action) {
        ConcurrentNavigableMap.super.forEach(action);
    }

    public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        ConcurrentNavigableMap.super.replaceAll(function);
    }
}
