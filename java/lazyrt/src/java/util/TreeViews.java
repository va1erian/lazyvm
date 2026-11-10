package java.util;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** The iterator, key set and entry set shared by TreeMap, its range views and TreeSet. */
final class TreeViews {
    private TreeViews() {
    }

    /** Marks a walk with no fence key: it runs to the end of the tree. */
    private static final Object UNFENCED = new Object();

    /**
     * Walks nodes in key order (or reverse) until the node whose key is `fence`. Spec 9.F2: next()
     * checks for the end before checking for a change.
     */
    static final class Walk<K, V> implements Iterator<Map.Entry<K, V>> {
        private final TreeMap<K, V> m;
        private final boolean ascending;
        private final Object fence;
        private MapNode<K, V> next;
        private MapNode<K, V> last;
        private int expected;
        /** A spliterator's walk does not check for a change at each step; it asks once at the end. */
        private boolean lenient;
        /** A lenient walk finds the node after the last one only when asked, so it sees additions made meanwhile. */
        private boolean stale;
        private Object lastKey;

        Walk(TreeMap<K, V> m, MapNode<K, V> start, MapNode<K, V> fenceNode, boolean ascending) {
            this.m = m;
            this.next = start;
            this.fence = fenceNode == null ? UNFENCED : fenceNode.key;
            this.ascending = ascending;
            this.expected = m.modCount;
        }

        private void refresh() {
            if (stale) {
                stale = false;
                // After a change the node may be gone or reused for another entry, so the next one is found by key;
                // without one the links are followed, which compares nothing.
                next = m.modCount != expected ? m.near(lastKey, ascending, false)
                        : ascending ? RedBlack.following(last) : RedBlack.preceding(last);
            }
        }

        public boolean hasNext() {
            refresh();
            return next != null && next.key != fence;
        }

        void beLenient() { lenient = true; }

        void checkEnd() {
            if (m.modCount != expected) {
                throw new ConcurrentModificationException();
            }
        }

        public Map.Entry<K, V> next() {
            refresh();
            MapNode<K, V> e = next;
            if (e == null || e.key == fence) {
                throw new NoSuchElementException();
            }
            if (m.modCount != expected && !lenient) {
                throw new ConcurrentModificationException();
            }
            last = e;
            if (lenient) {
                lastKey = e.key;
                stale = true;
            } else {
                next = ascending ? RedBlack.following(e) : RedBlack.preceding(e);
            }
            return e;
        }

        // Spec 6.M2: deleting a node with two children moves its successor's content into it,
        // so an ascending walk revisits that node.
        public void remove() {
            if (last == null) {
                throw new IllegalStateException();
            }
            if (m.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            if (ascending && last.left != null && last.right != null) {
                next = last;
            }
            m.discard(last);
            expected = m.modCount;
            last = null;
        }
    }

    /** The characteristics of the keys or entries of a map: sorted and sized unless it is reversed or a range. */
    static int flagsOf(NavigableMap<?, ?> m) {
        int ch = Spliterator.ORDERED | Spliterator.DISTINCT;
        if (!(m instanceof TreeRange)) {
            return ch | Spliterator.SORTED | Spliterator.SIZED;
        }
        TreeRange<?, ?> r = (TreeRange<?, ?>) m;
        if (!r.isReversed()) {
            ch |= Spliterator.SORTED;
        }
        return r.isBounded() ? ch : ch | Spliterator.SIZED;
    }

    /** Walks keys or entries of a tree; a change made during the walk is reported when it is over. */
    static final class Split<K, V, T> extends Spliterators.AbstractSpliterator<T> {
        private final Supplier<Iterator<Map.Entry<K, V>>> walk;
        private final Function<Map.Entry<K, V>, T> pick;
        private final NavigableMap<K, V> m;
        private final int ch;
        private final Comparator<? super T> cmp;
        private Iterator<Map.Entry<K, V>> it;

        Split(Supplier<Iterator<Map.Entry<K, V>>> walk, Function<Map.Entry<K, V>, T> pick, NavigableMap<K, V> m,
                Comparator<? super T> cmp) {
            super(Long.MAX_VALUE, 0);
            this.walk = walk;
            this.pick = pick;
            this.m = m;
            this.ch = flagsOf(m);
            this.cmp = cmp;
        }

        private Iterator<Map.Entry<K, V>> bind() {
            if (it == null) {
                it = walk.get();
                if (it instanceof Walk) {
                    ((Walk<K, V>) it).beLenient();
                }
                if ((ch & SIZED) != 0) {
                    remaining = m.size();
                }
            }
            return it;
        }

        private void checkEnd() {
            if (it instanceof Walk) {
                ((Walk<K, V>) it).checkEnd();
            }
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            Iterator<Map.Entry<K, V>> i = bind();
            if (!i.hasNext()) {
                return false;
            }
            action.accept(pick.apply(i.next()));
            checkEnd();
            return true;
        }

        @Override
        public void forEachRemaining(Consumer<? super T> action) {
            Objects.requireNonNull(action);
            Iterator<Map.Entry<K, V>> i = bind();
            while (i.hasNext()) {
                action.accept(pick.apply(i.next()));
            }
            checkEnd();
        }

        @Override
        public Spliterator<T> trySplit() {
            bind();
            return super.trySplit();
        }

        @Override
        public long estimateSize() {
            bind();
            return remaining;
        }

        @Override
        public int characteristics() { return ch; }

        @Override
        public Comparator<? super T> getComparator() {
            if ((ch & SORTED) != 0) {
                return cmp;
            }
            throw new IllegalStateException();
        }
    }

    /** The entries of a TreeMap or of one of its range views. */
    static final class Entries<K, V> extends AbstractSet<Map.Entry<K, V>> {
        private final NavigableMap<K, V> m;
        private final TreeMap<K, V> tree;
        private final Supplier<Iterator<Map.Entry<K, V>>> walk;
        private final Function<Object, MapNode<K, V>> find;

        /** The entries of view m of tree; `find` gives the in-view node for a key. */
        Entries(NavigableMap<K, V> m, TreeMap<K, V> tree, Supplier<Iterator<Map.Entry<K, V>>> walk,
                Function<Object, MapNode<K, V>> find) {
            this.m = m;
            this.tree = tree;
            this.walk = walk;
            this.find = find;
        }

        public Iterator<Map.Entry<K, V>> iterator() { return walk.get(); }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Spliterator<Map.Entry<K, V>> spliterator() {
            Comparator<? super K> kc = m.comparator();
            Comparator<Map.Entry<K, V>> byKey = kc == null ? (Comparator) Map.Entry.comparingByKey()
                    : Map.Entry.comparingByKey(kc);
            return new Split<K, V, Map.Entry<K, V>>(walk, e -> e, m, byKey);
        }
        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        // A view clears entry by entry through its walk; only the whole map clears at once.
        public void clear() {
            if (m == tree) {
                tree.clear();
            } else {
                super.clear();
            }
        }

        /** The node matching entry o (key and value), or null. */
        private MapNode<K, V> match(Object o) {
            if (!(o instanceof Map.Entry)) {
                return null;
            }
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            MapNode<K, V> n = find.apply(e.getKey());
            // Spec 8.C3: the stored value's equals is asked, even about a null.
            return n != null && (n.value == null ? e.getValue() == null : n.value.equals(e.getValue())) ? n : null;
        }

        public boolean contains(Object o) { return match(o) != null; }

        public boolean remove(Object o) {
            MapNode<K, V> n = match(o);
            if (n == null) {
                return false;
            }
            tree.discard(n);
            return true;
        }
    }

    /** The keys of a TreeMap or range view, as a navigable set that cannot add. */
    static final class Keys<E> extends AbstractSet<E> implements NavigableSet<E> {
        private final NavigableMap<E, ?> m;

        Keys(NavigableMap<E, ?> m) { this.m = m; }

        static <E> Iterator<E> keysOf(Iterator<? extends Map.Entry<E, ?>> it) {
            return new Iterator<E>() {
                public boolean hasNext() { return it.hasNext(); }
                public E next() { return it.next().getKey(); }
                public void remove() { it.remove(); }
            };
        }

        public Iterator<E> iterator() { return keysOf(m.entrySet().iterator()); }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Spliterator<E> spliterator() {
            NavigableMap<E, Object> map = (NavigableMap) m;
            return new Split<E, Object, E>(() -> (Iterator) map.entrySet().iterator(), e -> e.getKey(), map,
                    m.comparator());
        }
        public Iterator<E> descendingIterator() { return keysOf(m.descendingMap().entrySet().iterator()); }
        public int size() { return m.size(); }
        public boolean isEmpty() { return m.isEmpty(); }
        public boolean contains(Object o) { return m.containsKey(o); }
        public void clear() { m.clear(); }
        public E lower(E e) { return m.lowerKey(e); }
        public E floor(E e) { return m.floorKey(e); }
        public E ceiling(E e) { return m.ceilingKey(e); }
        public E higher(E e) { return m.higherKey(e); }
        public E first() { return m.firstKey(); }
        public E last() { return m.lastKey(); }
        public Comparator<? super E> comparator() { return m.comparator(); }
        public E pollFirst() { return keyOf(m.pollFirstEntry()); }
        public E pollLast() { return keyOf(m.pollLastEntry()); }
        public NavigableSet<E> descendingSet() { return new Keys<E>(m.descendingMap()); }
        public NavigableSet<E> subSet(E from, boolean fromIn, E to, boolean toIn) {
            return new Keys<E>(m.subMap(from, fromIn, to, toIn));
        }
        public NavigableSet<E> headSet(E to, boolean in) { return new Keys<E>(m.headMap(to, in)); }
        public NavigableSet<E> tailSet(E from, boolean in) { return new Keys<E>(m.tailMap(from, in)); }
        public SortedSet<E> subSet(E from, E to) { return subSet(from, true, to, false); }
        public SortedSet<E> headSet(E to) { return headSet(to, false); }
        public SortedSet<E> tailSet(E from) { return tailSet(from, true); }

        public boolean remove(Object o) {
            int before = m.size();
            m.remove(o);
            return m.size() != before;
        }

        static <E> E keyOf(Map.Entry<E, ?> e) { return e == null ? null : e.getKey(); }
    }
}
