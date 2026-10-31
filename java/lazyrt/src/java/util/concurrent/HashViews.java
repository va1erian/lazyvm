package java.util.concurrent;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** What the three views of a {@link ConcurrentHashMap} share, and the two that are not public. */
abstract class HashViews<K, V, E> extends AbstractCollection<E> {
    final ConcurrentHashMap<K, V> map;

    HashViews(ConcurrentHashMap<K, V> map) {
        this.map = map;
    }

    public final void clear() {
        map.clear();
    }

    public final int size() {
        return map.size();
    }

    public final boolean isEmpty() {
        return map.isEmpty();
    }

    public final Object[] toArray() {
        return snapshot().toArray();
    }

    public final <T> T[] toArray(T[] a) {
        return snapshot().toArray(a);
    }

    private ArrayList<E> snapshot() {
        ArrayList<E> out = new ArrayList<>();
        for (Iterator<E> it = iterator(); it.hasNext();) {
            out.add(it.next());
        }
        return out;
    }

    public final String toString() {
        return super.toString();
    }

    /** A null is simply not there, though {@code contains(null)} of two views throws (concurrent.md). */
    public final boolean containsAll(Collection<?> c) {
        for (Object o : c) {
            if (o == null || !contains(o)) {
                return false;
            }
        }
        return true;
    }

    public final boolean removeAll(Collection<?> c) {
        return super.removeAll(c);
    }

    public final boolean retainAll(Collection<?> c) {
        return super.retainAll(c);
    }

    /** The set form of equals and hashCode, for the key view and the entry view. */
    final boolean sameElements(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof Set)) {
            return false;
        }
        Collection<?> other = (Collection<?>) o;
        return containsAll(other) && other.containsAll(this);
    }

    final int elementHashes() {
        return hashes(iterator());
    }

    static int hashes(Iterator<?> it) {
        int h = 0;
        while (it.hasNext()) {
            h += it.next().hashCode();
        }
        return h;
    }

    static boolean holds(Iterator<?> it, Object value) {
        while (it.hasNext()) {
            Object v = it.next();
            if (v == value || value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** The usual form of a map's text, from its own walk: {@code toString} asks no public method. */
    static <K, V> String text(ConcurrentHashMap<K, V> map) {
        StringBuilder sb = new StringBuilder("{");
        for (Iterator<Map.Entry<K, V>> it = map.entryWalk(); it.hasNext();) {
            Map.Entry<K, V> e = it.next();
            sb.append(e.getKey() == map ? "(this Map)" : e.getKey()).append('=').append(e.getValue() == map ? "(this Map)" : e.getValue());
            if (it.hasNext()) {
                sb.append(", ");
            }
        }
        return sb.append('}').toString();
    }

    /** Both ways round, with no size comparison: the sizes may be changing. */
    static <K, V> boolean sameMappings(ConcurrentHashMap<K, V> map, Map<?, ?> m) {
        for (Iterator<Map.Entry<K, V>> it = map.entryWalk(); it.hasNext();) {
            Map.Entry<K, V> e = it.next();
            Object theirs = m.get(e.getKey());
            if (theirs == null || (theirs != e.getValue() && !theirs.equals(e.getValue()))) {
                return false;
            }
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) {
                return false;
            }
            V mine = map.get(k);
            if (mine == null || (v != mine && !v.equals(mine))) {
                return false;
            }
        }
        return true;
    }

    static final class KeyWalk<K, V> extends HashWalk<K, V, K> {
        KeyWalk(HashCore<K, V> core) {
            super(core);
        }

        @Override
        K make(K key, V value) {
            return key;
        }
    }

    static final class ValueWalk<K, V> extends HashWalk<K, V, V> {
        ValueWalk(HashCore<K, V> core) {
            super(core);
        }

        @Override
        V make(K key, V value) {
            return value;
        }
    }

    static final class EntryWalk<K, V> extends HashWalk<K, V, Map.Entry<K, V>> {
        private final ConcurrentHashMap<K, V> map;

        EntryWalk(ConcurrentHashMap<K, V> map, HashCore<K, V> core) {
            super(core);
            this.map = map;
        }

        @Override
        Map.Entry<K, V> make(K key, V value) {
            return new LiveEntry<>(map, key, value);
        }
    }

    /** {@code Map.Entry}'s manners, and a {@code setValue} that writes through. */
    static final class LiveEntry<K, V> extends AbstractMap.SimpleEntry<K, V> {
        private final ConcurrentHashMap<K, V> map;

        LiveEntry(ConcurrentHashMap<K, V> map, K key, V value) {
            super(key, value);
            this.map = map;
        }

        @Override
        public V setValue(V value) {
            Objects.requireNonNull(value);
            map.put(getKey(), value);
            return super.setValue(value);
        }
    }

    static final class Values<K, V> extends HashViews<K, V, V> {
        Values(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public boolean contains(Object o) {
            return map.containsValue(o);
        }

        public boolean remove(Object o) {
            if (o == null) {
                return false;
            }
            for (Iterator<V> it = iterator(); it.hasNext();) {
                if (o.equals(it.next())) {
                    it.remove();
                    return true;
                }
            }
            return false;
        }

        public Iterator<V> iterator() {
            return map.valueWalk();
        }

        public boolean add(V e) {
            throw new UnsupportedOperationException();
        }

        public boolean addAll(Collection<? extends V> c) {
            throw new UnsupportedOperationException();
        }
    }

    static final class Entries<K, V> extends HashViews<K, V, Map.Entry<K, V>> implements Set<Map.Entry<K, V>> {
        Entries(ConcurrentHashMap<K, V> map) {
            super(map);
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object k = e.getKey();
            Object v = e.getValue();
            if (k == null || v == null) {
                return false;
            }
            V mapped = map.get(k);
            return mapped != null && (v == mapped || v.equals(mapped));
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            Object k = e.getKey();
            Object v = e.getValue();
            return k != null && v != null && map.remove(k, v);
        }

        public Iterator<Map.Entry<K, V>> iterator() {
            return map.entryWalk();
        }

        /** Writes the value in any case, and says only whether the key was new (concurrent.md). */
        public boolean add(Map.Entry<K, V> e) {
            return map.core.put(e.getKey(), Objects.requireNonNull(e.getValue()), false) == null;
        }

        public boolean addAll(Collection<? extends Map.Entry<K, V>> c) {
            boolean grew = false;
            for (Map.Entry<K, V> e : c) {
                grew |= add(e);
            }
            return grew;
        }

        public boolean equals(Object o) {
            return sameElements(o);
        }

        public int hashCode() {
            return elementHashes();
        }
    }
}
