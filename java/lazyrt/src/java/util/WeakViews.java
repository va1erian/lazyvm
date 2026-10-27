package java.util;

import java.util.function.Function;

/** WeakHashMap's three views and their iterator (docs/design/weakhashmap.md W13, W17 to W26). */
final class WeakViews {
    private WeakViews() {
    }

    /**
     * Walks the table from the top bucket down (W2). {@code pending} is the next link to look at
     * in bucket {@code slot}, taken from its predecessor when that one was looked at; null means
     * the next step reads the head of the bucket below. A pending link may since have been dropped
     * by the map, which is fine because a dropped link keeps its successor (W22). The two key
     * fields are strong holds (W13): the key hasNext found, and the key next returned.
     */
    static final class Cursor<K, V, T> implements Iterator<T> {
        private final WeakHashMap<K, V> map;
        private final Function<WeakLink<K, V>, T> pick;
        private int expected;
        private int slot;
        private WeakLink<K, V> pending;
        private WeakLink<K, V> found;
        private Object foundKey;
        private WeakLink<K, V> returned;
        private Object returnedKey;

        Cursor(WeakHashMap<K, V> map, Function<WeakLink<K, V>, T> pick) {
            this.map = map;
            this.pick = pick;
            this.expected = map.edits;
            // W23: an iterator made on an empty map has no buckets left to read.
            this.slot = map.isEmpty() ? 0 : map.table.length;
        }

        /** W18, W23: never throws; reads bucket heads as they are now; skips cleared keys (W13). */
        public boolean hasNext() {
            while (found == null) {
                WeakLink<K, V> l = pending;
                if (l == null) {
                    if (slot == 0) {
                        // W26: the end of the walk lets go of the returned key too.
                        returnedKey = null;
                        return false;
                    }
                    pending = map.table[--slot];
                    continue;
                }
                pending = l.next;
                Object key = l.get();
                if (key != null || l.holdsNull) {
                    found = l;
                    foundKey = key;
                }
            }
            return true;
        }

        /** W18: a change is reported before the end is. */
        public T next() {
            if (map.edits != expected) {
                throw new ConcurrentModificationException();
            }
            if (!hasNext()) {
                throw Support.noElement();
            }
            returned = found;
            returnedKey = foundKey;
            found = null;
            foundKey = null;
            return pick.apply(returned);
        }

        /**
         * W26: nothing to remove comes first, then a change. Removal is remove(key) of the held
         * key, so once the walk has reported its end it is remove(null).
         */
        public void remove() {
            if (returned == null) {
                throw new IllegalStateException();
            }
            if (map.edits != expected) {
                throw new ConcurrentModificationException();
            }
            map.remove(returnedKey);
            expected = map.edits;
            returned = null;
            returnedKey = null;
        }
    }

    /** W24: contains is containsKey; remove is containsKey followed by remove. */
    static final class Keys<K, V> extends AbstractSet<K> {
        private final WeakHashMap<K, V> m;

        Keys(WeakHashMap<K, V> m) { this.m = m; }
        public Iterator<K> iterator() { return new Cursor<>(m, WeakLink::getKey); }
        public int size() { return m.size(); }
        public boolean contains(Object o) { return m.containsKey(o); }

        public boolean remove(Object o) {
            if (!m.containsKey(o)) {
                return false;
            }
            m.remove(o);
            return true;
        }

        public void clear() { m.clear(); }
    }

    /** W25: contains is containsValue; remove is the first match through the iterator (W17). */
    static final class Values<K, V> extends AbstractCollection<V> {
        private final WeakHashMap<K, V> m;

        Values(WeakHashMap<K, V> m) { this.m = m; }
        public Iterator<V> iterator() { return new Cursor<>(m, WeakLink::getValue); }
        public int size() { return m.size(); }
        public boolean contains(Object o) { return m.containsValue(o); }
        public void clear() { m.clear(); }
    }

    /** W16, W24: the links themselves are the entries. */
    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final WeakHashMap<K, V> m;

        Entries(WeakHashMap<K, V> m) { this.m = m; }
        public Iterator<Map.Entry<K, V>> iterator() { return new Cursor<>(m, l -> l); }
        public int size() { return m.size(); }

        /** W24: look the key up as get does, then ask the stored entry whether it equals o. */
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            WeakLink<K, V> l = m.linkFor(((Map.Entry<?, ?>) o).getKey());
            return l != null && l.equals(o);
        }

        public boolean remove(Object o) { return o instanceof Map.Entry && m.removeEntry((Map.Entry<?, ?>) o); }
        public void clear() { m.clear(); }
    }
}
