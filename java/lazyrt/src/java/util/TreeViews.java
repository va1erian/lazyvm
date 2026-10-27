package java.util;

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

        Walk(TreeMap<K, V> m, MapNode<K, V> start, MapNode<K, V> fenceNode, boolean ascending) {
            this.m = m;
            this.next = start;
            this.fence = fenceNode == null ? UNFENCED : fenceNode.key;
            this.ascending = ascending;
            this.expected = m.modCount;
        }

        public boolean hasNext() { return next != null && next.key != fence; }

        public Map.Entry<K, V> next() {
            MapNode<K, V> e = next;
            if (e == null || e.key == fence) {
                throw new NoSuchElementException();
            }
            if (m.modCount != expected) {
                throw new ConcurrentModificationException();
            }
            next = ascending ? RedBlack.following(e) : RedBlack.preceding(e);
            last = e;
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
