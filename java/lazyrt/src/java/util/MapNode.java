package java.util;

/**
 * A map entry that can sit in a red-black tree: TreeMap's entries, and HashMap's entries when
 * their bin is a tree (spec 1.H7). The tree links are unused while a HashMap bin is a list.
 */
class MapNode<K, V> implements Map.Entry<K, V> {
    K key;
    V value;
    MapNode<K, V> left;
    MapNode<K, V> right;
    MapNode<K, V> up;
    boolean red;

    MapNode(K key, V value) {
        this.key = key;
        this.value = value;
    }

    public final K getKey() { return key; }
    public final V getValue() { return value; }

    public V setValue(V value) {
        V old = this.value;
        this.value = value;
        return old;
    }

    // Spec 8.C3: a TreeMap entry asks its own key's and value's equals, even about itself; a
    // HashMap entry compares identity first (HashMap.Cell).
    public boolean equals(Object o) { return AbstractMap.entryEquals(this, o); }
    public final int hashCode() { return AbstractMap.entryHash(this); }
    public final String toString() { return key + "=" + value; }

    final void unlinkTree() {
        left = null;
        right = null;
        up = null;
        red = false;
    }
}
