package java.util.concurrent;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The defaults go through the map's conditional writes and retry one that lost: the map may be
 * changing under them. An entry that throws {@code IllegalStateException} has left the map, and is
 * skipped (concurrent.md, "ConcurrentMap defaults").
 */
public interface ConcurrentMap<K, V> extends Map<K, V> {
    @Override
    default V getOrDefault(Object key, V defaultValue) {
        V v = get(key);
        return v != null ? v : defaultValue;
    }

    @Override
    default void forEach(BiConsumer<? super K, ? super V> action) {
        Objects.requireNonNull(action);
        for (Map.Entry<K, V> e : entrySet()) {
            K k;
            V v;
            try {
                k = e.getKey();
                v = e.getValue();
            } catch (IllegalStateException gone) {
                continue;
            }
            action.accept(k, v);
        }
    }

    V putIfAbsent(K key, V value);

    boolean remove(Object key, Object value);

    boolean replace(K key, V oldValue, V newValue);

    V replace(K key, V value);

    @Override
    default void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
        Objects.requireNonNull(function);
        forEach((k, v) -> {
            while (!replace(k, v, function.apply(k, v))) {
                v = get(k);
                if (v == null) {
                    break;
                }
            }
        });
    }

    @Override
    default V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        Objects.requireNonNull(mappingFunction);
        V v = get(key);
        if (v != null) {
            return v;
        }
        V fresh = mappingFunction.apply(key);
        if (fresh == null) {
            return null;
        }
        V raced = putIfAbsent(key, fresh);
        return raced == null ? fresh : raced;
    }

    @Override
    default V computeIfPresent(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        V old;
        while ((old = get(key)) != null) {
            V fresh = remappingFunction.apply(key, old);
            if (fresh != null ? replace(key, old, fresh) : remove(key, old)) {
                return fresh;
            }
        }
        return null;
    }

    @Override
    default V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        for (;;) {
            V old = get(key);
            V fresh = remappingFunction.apply(key, old);
            if (fresh == null) {
                if (old == null || remove(key, old)) {
                    return null;
                }
            } else if (old == null ? putIfAbsent(key, fresh) == null : replace(key, old, fresh)) {
                return fresh;
            }
        }
    }

    @Override
    default V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        Objects.requireNonNull(remappingFunction);
        Objects.requireNonNull(value);
        for (;;) {
            V old = get(key);
            if (old == null) {
                if (putIfAbsent(key, value) == null) {
                    return value;
                }
                continue;
            }
            V fresh = remappingFunction.apply(old, value);
            if (fresh != null ? replace(key, old, fresh) : remove(key, old)) {
                return fresh;
            }
        }
    }
}
