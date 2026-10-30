package java.util.concurrent;

import java.util.AbstractMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToLongBiFunction;

/** The folds behind {@link ConcurrentHashMap}'s bulk methods: one weak walk on the calling thread. */
final class HashBulk<K, V> {
    private final HashCore<K, V> core;

    HashBulk(HashCore<K, V> core) {
        this.core = core;
    }

    /** A walk that hands the pair itself on; only a fold needs no allocation per step. */
    private final class Pair extends HashCore.Walk<K, V, Pair> {
        K key;
        V value;

        Pair() {
            super(core);
        }

        @Override
        Pair make(K k, V v) {
            key = k;
            value = v;
            return this;
        }
    }

    Map.Entry<K, V> entry(K key, V value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    /** Stops at the first non-null answer and returns it. */
    <U> U visit(BiFunction<? super K, ? super V, ? extends U> f) {
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            U u = f.apply(p.key, p.value);
            if (u != null) {
                return u;
            }
        }
        return null;
    }

    <U> void feed(BiFunction<? super K, ? super V, ? extends U> f, Consumer<? super U> action) {
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            U u = f.apply(p.key, p.value);
            if (u != null) {
                action.accept(u);
            }
        }
    }

    <U> U fold(BiFunction<? super K, ? super V, ? extends U> f, BiFunction<? super U, ? super U, ? extends U> reducer) {
        U total = null;
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            U u = f.apply(p.key, p.value);
            if (u != null) {
                total = total == null ? u : reducer.apply(total, u);
            }
        }
        return total;
    }

    double foldDouble(ToDoubleBiFunction<? super K, ? super V> f, double basis, DoubleBinaryOperator reducer) {
        double total = basis;
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            total = reducer.applyAsDouble(total, f.applyAsDouble(p.key, p.value));
        }
        return total;
    }

    long foldLong(ToLongBiFunction<? super K, ? super V> f, long basis, LongBinaryOperator reducer) {
        long total = basis;
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            total = reducer.applyAsLong(total, f.applyAsLong(p.key, p.value));
        }
        return total;
    }

    int foldInt(ToIntBiFunction<? super K, ? super V> f, int basis, IntBinaryOperator reducer) {
        int total = basis;
        for (Pair p = new Pair(); p.hasNext();) {
            p.next();
            total = reducer.applyAsInt(total, f.applyAsInt(p.key, p.value));
        }
        return total;
    }
}
