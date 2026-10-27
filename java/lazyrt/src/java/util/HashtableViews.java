package java.util;

import java.util.function.Function;

/** Hashtable's enumerations, iterators and views. */
final class HashtableViews {
    private HashtableViews() {
    }


    /**
     * Spec 2.T1: buckets from the last down, each chain front to back. Spec 9.F2: as an iterator,
     * next() checks for a change before the end.
     */
    static final class Walk<K, V, T> implements Enumeration<T>, Iterator<T> {
        private final Hashtable<K, V> owner;
        private final Hashtable.Link<K, V>[] table;
        private final boolean removable;
        private final Function<Hashtable.Link<K, V>, T> pick;
        private int bucketAt;
        private Hashtable.Link<K, V> pending;
        private Hashtable.Link<K, V> last;
        private int expected;

        /** `removable` for an iterator; an enumeration refuses remove(). */
        Walk(Hashtable<K, V> owner, Hashtable.Link<K, V>[] table, Function<Hashtable.Link<K, V>, T> pick,
                boolean removable) {
            this.owner = owner;
            this.table = table;
            this.pick = pick;
            this.removable = removable;
            this.bucketAt = table.length;
            this.expected = owner.modCount;
        }

        public boolean hasMoreElements() {
            while (pending == null && bucketAt > 0) {
                pending = table[--bucketAt];
            }
            return pending != null;
        }

        public T nextElement() {
            if (!hasMoreElements()) {
                throw new NoSuchElementException("Hashtable Enumerator");
            }
            last = pending;
            pending = pending.next;
            return pick.apply(last);
        }

        public boolean hasNext() { return hasMoreElements(); }

        public T next() {
            if (owner.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            return nextElement();
        }

        public void remove() {
            if (!removable) {
                throw new UnsupportedOperationException();
            }
            if (last == null) {
                throw new IllegalStateException("Hashtable Enumerator");
            }
            if (owner.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            synchronized (owner) {
                owner.removeEntry(last);
                expected = owner.modCount;
                last = null;
            }
        }
    }

    static final class Keys<K, V> extends AbstractSet<K> {
        private final Hashtable<K, V> t;

        Keys(Hashtable<K, V> t) { this.t = t; }
        public Iterator<K> iterator() { return t.iterator(e -> e.key); }
        public int size() { return t.size(); }
        public boolean contains(Object o) { return t.containsKey(o); }
        public boolean remove(Object o) { return t.remove(o) != null; }
        public void clear() { t.clear(); }
    }

    static final class Values<K, V> extends AbstractCollection<V> {
        private final Hashtable<K, V> t;

        Values(Hashtable<K, V> t) { this.t = t; }
        public Iterator<V> iterator() { return t.iterator(e -> e.value); }
        public int size() { return t.size(); }
        public boolean contains(Object o) { return t.containsValue(o); }
        public void clear() { t.clear(); }
    }

    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final Hashtable<K, V> t;

        Entries(Hashtable<K, V> t) { this.t = t; }

        // Spec 2.T5: the element is cast to Map.Entry before add refuses it.
        public boolean add(Map.Entry<K, V> e) { return super.add(e); }
        public Iterator<Map.Entry<K, V>> iterator() { return t.iterator(e -> e); }
        public int size() { return t.size(); }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            synchronized (t) {
                Hashtable.Link<K, V> found = t.entryFor(e.getKey());
                return found != null && found.equals(e);
            }
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            synchronized (t) {
                Hashtable.Link<K, V> found = t.entryFor(e.getKey());
                if (found == null || !found.equals(e)) {
                    return false;
                }
                t.removeEntry(found);
                found.value = null;
                return true;
            }
        }

        public void clear() { t.clear(); }
    }
}
