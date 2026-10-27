package java.util;

/** WeakHashMap's three views and their iterators (docs/design/weakhashmap.md W2, W13, W17, W18). */
final class WeakHashViews {
    private WeakHashViews() {
    }

    /**
     * W2: buckets from the top down, each chain front to back. W13: the key found by hasNext and the
     * key last returned are held strongly, so the collector cannot take them mid-step.
     */
    abstract static class Walk<K, V, T> implements Iterator<T> {
        private final WeakHashMap<K, V> map;
        private int index;
        private WeakHashMap.WeakEntry<K, V> entry;
        private WeakHashMap.WeakEntry<K, V> lastReturned;
        private int expectedModCount;
        private Object nextKey;
        private Object currentKey;

        Walk(WeakHashMap<K, V> map) {
            this.map = map;
            expectedModCount = map.modCount;
            index = map.isEmpty() ? 0 : map.liveTable().length;
        }

        /** W18: never throws, and reads the table as it is now. */
        @Override
        public boolean hasNext() {
            WeakHashMap.WeakEntry<K, V>[] tab = map.liveTable();
            while (nextKey == null) {
                WeakHashMap.WeakEntry<K, V> e = entry;
                int i = index;
                while (e == null && i > 0) {
                    e = tab[--i];
                }
                entry = e;
                index = i;
                if (e == null) {
                    currentKey = null;
                    return false;
                }
                nextKey = e.get(); // W13: cleared keys are skipped
                if (nextKey == null) {
                    entry = e.next;
                }
            }
            return true;
        }

        /** W18: only next() checks for changes. */
        WeakHashMap.WeakEntry<K, V> nextEntry() {
            if (map.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (nextKey == null && !hasNext()) {
                throw new NoSuchElementException();
            }
            lastReturned = entry;
            entry = entry.next;
            currentKey = nextKey;
            nextKey = null;
            return lastReturned;
        }

        @Override
        public void remove() {
            if (lastReturned == null) {
                throw new IllegalStateException();
            }
            if (map.modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            map.removeEntry(WeakHashMap.unmask(currentKey));
            expectedModCount = map.modCount;
            lastReturned = null;
            currentKey = null;
        }
    }

    static final class Keys<K, V> extends AbstractSet<K> {
        private final WeakHashMap<K, V> map;

        Keys(WeakHashMap<K, V> map) { this.map = map; }

        @Override
        public Iterator<K> iterator() {
            return new Walk<K, V, K>(map) {
                @Override
                public K next() { return nextEntry().getKey(); }
            };
        }

        @Override
        public int size() { return map.size(); }

        @Override
        public boolean contains(Object o) { return map.containsKey(o); }

        @Override
        public boolean remove(Object o) { return map.removeEntry(o) != null; }

        @Override
        public void clear() { map.clear(); }
    }

    static final class Values<K, V> extends AbstractCollection<V> {
        private final WeakHashMap<K, V> map;

        Values(WeakHashMap<K, V> map) { this.map = map; }

        @Override
        public Iterator<V> iterator() {
            return new Walk<K, V, V>(map) {
                @Override
                public V next() { return nextEntry().value; }
            };
        }

        @Override
        public int size() { return map.size(); }

        @Override
        public boolean contains(Object o) { return map.containsValue(o); }

        @Override
        public void clear() { map.clear(); }
    }

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final WeakHashMap<K, V> map;

        Entries(WeakHashMap<K, V> map) { this.map = map; }

        @Override
        public Iterator<Map.Entry<K, V>> iterator() {
            return new Walk<K, V, Map.Entry<K, V>>(map) {
                @Override
                public Map.Entry<K, V> next() { return nextEntry(); }
            };
        }

        @Override
        public int size() { return map.size(); }

        /** W16: the key is looked up, then the values compared. */
        @Override
        public boolean contains(Object o) { return matching(o) != null; }

        @Override
        public boolean remove(Object o) {
            WeakHashMap.WeakEntry<K, V> e = matching(o);
            if (e == null) {
                return false;
            }
            map.removeEntry(e.getKey());
            return true;
        }

        private WeakHashMap.WeakEntry<K, V> matching(Object o) {
            if (!(o instanceof Map.Entry)) {
                return null;
            }
            Map.Entry<?, ?> probe = (Map.Entry<?, ?>) o;
            WeakHashMap.WeakEntry<K, V> e = map.getEntry(probe.getKey());
            return e != null && Objects.equals(e.value, probe.getValue()) ? e : null;
        }

        @Override
        public void clear() { map.clear(); }
    }
}
