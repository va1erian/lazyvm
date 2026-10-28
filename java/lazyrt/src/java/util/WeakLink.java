package java.util;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * One mapping of a WeakHashMap: a weak reference to the key, registered with the map's queue so
 * the map learns when the collector cleared it, plus the value (held strongly, W12), the key's
 * hash code as computed when it was stored (W15) and the next link of its bucket's chain (W3).
 *
 * The null key is a link whose referent is null and whose {@code holdsNull} is set: the collector
 * has nothing to clear there, so it stays until removed (W12). Any other link whose referent is
 * null has been cleared and is waiting to be dropped.
 */
final class WeakLink<K, V> extends WeakReference<Object> implements Map.Entry<K, V> {
    final int hash;
    final boolean holdsNull;
    V value;
    /**
     * The successor in the chain. Unlinking a link never resets this, so an iterator parked on a
     * link the map has since dropped can still reach the survivors after it (W22).
     */
    WeakLink<K, V> next;

    WeakLink(Object key, int hash, V value, WeakLink<K, V> next, ReferenceQueue<Object> queue) {
        super(key, queue);
        this.hash = hash;
        this.holdsNull = key == null;
        this.value = value;
        this.next = next;
    }

    /** Whether this link still maps a key: the null key, or a key the collector has not cleared. */
    boolean live() { return holdsNull || get() != null; }

    /** W14: null once the key is cleared; the null key's link also reports null. */
    @SuppressWarnings("unchecked")
    public K getKey() { return (K) get(); }

    public V getValue() { return value; }

    public V setValue(V v) {
        V old = value;
        value = v;
        return old;
    }

    /** W16, W24: identity first, then this entry's key's and value's equals, never the argument's. */
    public boolean equals(Object o) { return AbstractMap.entrySame(this, o); }

    public int hashCode() { return AbstractMap.entryHash(this); }

    public String toString() { return getKey() + "=" + getValue(); }
}
