package java.util.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A weak walk over the table as it was at the start. After a resize that table no longer
 * changes, so each pair is checked against the map: what has left since is skipped, and a
 * changed value is seen. Holds the next pair from the moment it finds it (G6). A chain it is
 * already on stays as it was, removed nodes included, as on the oracle (concurrent.md); so does
 * a long bin, whose entries it takes in one go when it reaches it.
 */
abstract class HashWalk<K, V, E> implements Iterator<E>, java.util.Enumeration<E> {
    private final HashCore<K, V> core;
    private final Object[] table;
    private int slot;
    /** The rest of the long bin this walk is in, which has no chain to follow. */
    private HashCore.Node<K, V>[] flat;
    private int flatAt;
    private HashCore.Node<K, V> upcoming;
    private V upcomingValue;
    private K last;

    HashWalk(HashCore<K, V> core) {
        this.core = core;
        this.table = core.tableToWalk();
        advance();
    }

    private void advance() {
        HashCore.Node<K, V> n = upcoming == null || flat != null ? null : upcoming.next;
        for (;;) {
            while (n == null) {
                if (flat != null && flatAt < flat.length) {
                    n = flat[flatAt++];
                } else if (slot == table.length) {
                    upcoming = null;
                    upcomingValue = null;
                    return;
                } else {
                    n = enterBin();
                }
            }
            V v = core.isCurrent(table) ? n.value : core.get(n.key);
            if (v != null) {
                upcoming = n;
                upcomingValue = v;
                return;
            }
            n = flat != null ? null : n.next;
        }
    }

    /** The first node of the next bin's chain; for a long bin nothing yet, with {@code flat} set. */
    @SuppressWarnings("unchecked")
    private HashCore.Node<K, V> enterBin() {
        Object s = table[slot++];
        boolean tree = s instanceof BinTreap;
        flat = tree ? ((BinTreap<K, V>) s).entries() : null;
        flatAt = 0;
        return tree ? null : (HashCore.Node<K, V>) s;
    }

    abstract E make(K key, V value);

    public final boolean hasNext() {
        return upcoming != null;
    }

    public final boolean hasMoreElements() {
        return upcoming != null;
    }

    public final E next() {
        if (upcoming == null) {
            throw new NoSuchElementException();
        }
        last = upcoming.key;
        E out = make(last, upcomingValue);
        advance();
        return out;
    }

    public final E nextElement() {
        return next();
    }

    public final void remove() {
        if (last == null) {
            throw new IllegalStateException();
        }
        core.change(last, null, null);
        last = null;
    }
}
