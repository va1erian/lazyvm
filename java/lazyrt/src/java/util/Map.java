package java.util;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface Map<K, V> {
    int size();

    boolean isEmpty();

    boolean containsKey(Object key);

    boolean containsValue(Object value);

    V get(Object key);

    V put(K key, V value);

    V remove(Object key);

    void putAll(Map<? extends K, ? extends V> m);

    void clear();

    Set<K> keySet();

    Collection<V> values();

    Set<Map.Entry<K, V>> entrySet();

    boolean equals(Object o);

    int hashCode();

    interface Entry<K, V> {
        K getKey();

        V getValue();

        V setValue(V value);

        boolean equals(Object o);

        int hashCode();

        public static <K extends Comparable<? super K>, V> Comparator<Map.Entry<K, V>> comparingByKey() {
            return (a, b) -> a.getKey().compareTo(b.getKey());
        }

        public static <K, V extends Comparable<? super V>> Comparator<Map.Entry<K, V>> comparingByValue() {
            return (a, b) -> a.getValue().compareTo(b.getValue());
        }

        public static <K, V> Comparator<Map.Entry<K, V>> comparingByKey(Comparator<? super K> cmp) {
            Objects.requireNonNull(cmp);
            return (a, b) -> cmp.compare(a.getKey(), b.getKey());
        }

        public static <K, V> Comparator<Map.Entry<K, V>> comparingByValue(Comparator<? super V> cmp) {
            Objects.requireNonNull(cmp);
            return (a, b) -> cmp.compare(a.getValue(), b.getValue());
        }
    }

    default V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v != null || containsKey(key) ? v : defaultValue;
    }

    default void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Map.Entry<K, V> e : entrySet()) {
            K k;
            V v;
            try {
                k = e.getKey();
                v = e.getValue();
            } catch (IllegalStateException gone) {
                throw new ConcurrentModificationException(gone);
            }
            action.accept(k, v);
        }
    }

    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        for (Map.Entry<K, V> e : entrySet()) {
            K k;
            V v;
            try {
                k = e.getKey();
                v = e.getValue();
            } catch (IllegalStateException gone) {
                throw new ConcurrentModificationException(gone);
            }
            v = function.apply(k, v);
            try {
                e.setValue(v);
            } catch (IllegalStateException gone) {
                throw new ConcurrentModificationException(gone);
            }
        }
    }

    default V putIfAbsent(K key, V value) {
        V v = get(key);
        if (v == null) {
            v = put(key, value);
        }
        return v;
    }

    default boolean remove(Object key, Object value) {
        Object current = get(key);
        if (!Objects.equals(current, value) || (current == null && !containsKey(key))) {
            return false;
        }
        remove(key);
        return true;
    }

    default boolean replace(K key, V oldValue, V newValue) {
        Object current = get(key);
        if (!Objects.equals(current, oldValue) || (current == null && !containsKey(key))) {
            return false;
        }
        put(key, newValue);
        return true;
    }

    default V replace(K key, V value) {
        V current = get(key);
        if (current != null || containsKey(key)) {
            current = put(key, value);
        }
        return current;
    }

    default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V v = get(key);
        if (v == null) {
            V fresh = mappingFunction.apply(key);
            if (fresh != null) {
                put(key, fresh);
                return fresh;
            }
        }
        return v;
    }

    default V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V old = get(key);
        if (old == null) {
            return null;
        }
        V fresh = remappingFunction.apply(key, old);
        if (fresh != null) {
            put(key, fresh);
        } else {
            remove(key);
        }
        return fresh;
    }

    default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V old = get(key);
        V fresh = remappingFunction.apply(key, old);
        if (fresh == null) {
            if (old != null || containsKey(key)) {
                remove(key);
            }
            return null;
        }
        put(key, fresh);
        return fresh;
    }

    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        V old = get(key);
        V fresh = old == null ? value : remappingFunction.apply(old, value);
        if (fresh == null) {
            remove(key);
        } else {
            put(key, fresh);
        }
        return fresh;
    }
}
