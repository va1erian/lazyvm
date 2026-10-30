package java.util.concurrent;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Function;

/**
 * The table of {@link ConcurrentHashMap}. Readers take no lock: a resize builds a new table of new
 * nodes and publishes it in one write, so a chain a reader walks only ever loses a node, which
 * keeps its link and value. Writers hold this object's monitor, only for the table work.
 *
 * <p>A computing function runs with no table lock, so other keys go on being read and written, and
 * the function may itself use the map. Instead the computation holds a {@link Claim} on its key: a
 * writer of that key blocks on the claim's monitor until it ends, as a {@code BLOCKED} thread. A
 * thread never waits for its own claim, so a recursive update of a key is only a lost update.
 */
final class HashCore<K, V> {
    static final class Node<K, V> {
        final int hash;
        final K key;
        V value;
        Node<K, V> next;

        Node(int hash, K key, V value) {
            this.hash = hash;
            this.key = key;
            this.value = value;
        }
    }

    private static final class Claim {
        final int hash;
        final Object key;
        final Thread thread = Thread.currentThread();
        Claim next;

        Claim(int hash, Object key) {
            this.hash = hash;
            this.key = key;
        }
    }

    private static final int MIN_SLOTS = 16;
    private static final int MAX_SLOTS = 1 << 30;

    private Node<K, V>[] table;
    private int size;
    private Claim claims;

    HashCore(int capacity) {
        int slots = MIN_SLOTS;
        while (slots < MAX_SLOTS && slots / 4 * 3 < capacity) {
            slots <<= 1;
        }
        table = newTable(slots);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int slots) {
        return (Node<K, V>[]) new Node<?, ?>[slots];
    }

    private static int spread(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    int size() {
        return size;
    }

    private Node<K, V> find(Object key, int hash) {
        Node<K, V>[] t = table;
        for (Node<K, V> n = t[hash & (t.length - 1)]; n != null; n = n.next) {
            if (n.hash == hash && (n.key == key || key.equals(n.key))) {
                return n;
            }
        }
        return null;
    }

    V get(Object key) {
        Node<K, V> n = find(key, spread(key));
        return n == null ? null : n.value;
    }

    boolean computing() {
        return claims != null;
    }

    private Claim claimOn(Object key, int hash) {
        Thread me = Thread.currentThread();
        for (Claim c = claims; c != null; c = c.next) {
            if (c.hash == hash && c.thread != me && (c.key == key || key.equals(c.key))) {
                return c;
            }
        }
        return null;
    }

    V put(K key, V value, boolean onlyIfAbsent) {
        int hash = spread(key);
        for (;;) {
            Claim held;
            synchronized (this) {
                held = claims == null ? null : claimOn(key, hash);
                if (held == null) {
                    Node<K, V> n = find(key, hash);
                    if (n == null) {
                        insert(key, hash, value);
                        return null;
                    }
                    V old = n.value;
                    if (!onlyIfAbsent) {
                        n.value = value;
                    }
                    return old;
                }
            }
            waitFor(held);
        }
    }

    /** Sets a present key to {@code update}, or removes it if that is null. Answers its old value. */
    V change(Object key, V update, Object expected) {
        int hash = spread(key);
        for (;;) {
            Claim held;
            synchronized (this) {
                held = claims == null ? null : claimOn(key, hash);
                if (held == null) {
                    Node<K, V> n = find(key, hash);
                    if (n == null) {
                        return null;
                    }
                    V old = n.value;
                    if (expected != null && expected != old && !expected.equals(old)) {
                        return null;
                    }
                    store(n, n.key, hash, update);
                    return old;
                }
            }
            waitFor(held);
        }
    }

    /** Blocks until that computation ends: whoever claimed holds the monitor throughout. */
    private void waitFor(Claim held) {
        synchronized (held) {
            // Nothing to do inside: having got in is the news.
        }
    }

    /**
     * The one way to compute: runs {@code step} on the value now mapped, alone among writers of
     * this key, and maps what it answers (or nothing, if null). A throwing step changes nothing.
     */
    V atomically(K key, Function<? super V, ? extends V> step) {
        int hash = spread(key);
        Claim mine = new Claim(hash, key);
        for (;;) {
            Claim held;
            synchronized (mine) {
                synchronized (this) {
                    held = claimOn(key, hash);
                    if (held == null) {
                        mine.next = claims;
                        claims = mine;
                    }
                }
                if (held == null) {
                    try {
                        V fresh = step.apply(get(key));
                        synchronized (this) {
                            store(find(key, hash), key, hash, fresh);
                        }
                        return fresh;
                    } finally {
                        release(mine);
                    }
                }
            }
            waitFor(held);
        }
    }

    private synchronized void release(Claim mine) {
        if (claims == mine) {
            claims = mine.next;
            return;
        }
        Claim c = claims;
        while (c.next != mine) {
            c = c.next;
        }
        c.next = mine.next;
    }

    private void store(Node<K, V> n, K key, int hash, V value) {
        if (n != null) {
            if (value != null) {
                n.value = value;
            } else {
                unlink(n);
            }
        } else if (value != null) {
            insert(key, hash, value);
        }
    }

    private void insert(K key, int hash, V value) {
        Node<K, V>[] t = table;
        int slot = hash & (t.length - 1);
        Node<K, V> fresh = new Node<>(hash, key, value);
        Node<K, V> n = t[slot];
        if (n == null) {
            t[slot] = fresh;
        } else {
            while (n.next != null) {
                n = n.next;
            }
            n.next = fresh;
        }
        size++;
        if (size > t.length / 4 * 3 && t.length < MAX_SLOTS) {
            grow(t);
        }
    }

    /** New nodes too: a reader or iterator on the old table keeps a table that no longer changes. */
    private void grow(Node<K, V>[] old) {
        Node<K, V>[] t = newTable(old.length << 1);
        Node<K, V>[] tails = newTable(t.length);
        for (Node<K, V> n : old) {
            for (; n != null; n = n.next) {
                int slot = n.hash & (t.length - 1);
                Node<K, V> copy = new Node<>(n.hash, n.key, n.value);
                if (tails[slot] == null) {
                    t[slot] = copy;
                } else {
                    tails[slot].next = copy;
                }
                tails[slot] = copy;
            }
        }
        table = t;
    }

    private void unlink(Node<K, V> gone) {
        Node<K, V>[] t = table;
        int slot = gone.hash & (t.length - 1);
        if (t[slot] == gone) {
            t[slot] = gone.next;
        } else {
            Node<K, V> n = t[slot];
            while (n.next != gone) {
                n = n.next;
            }
            n.next = gone.next;
        }
        size--;
    }

    synchronized void clear() {
        Node<K, V>[] t = table;
        for (int i = 0; i < t.length; i++) {
            t[i] = null;
        }
        size = 0;
    }

    /**
     * A weak walk over the table as it was at the start. After a resize that table no longer
     * changes, so each pair is checked against the map: what has left since is skipped, and a
     * changed value is seen. Holds the next pair from the moment it finds it (G6). A chain it is
     * already on stays as it was, removed nodes included, as on the oracle (concurrent.md).
     */
    abstract static class Walk<K, V, E> implements Iterator<E>, java.util.Enumeration<E> {
        private final HashCore<K, V> core;
        private final Node<K, V>[] table;
        private int slot;
        private Node<K, V> upcoming;
        private V upcomingValue;
        private K last;

        Walk(HashCore<K, V> core) {
            this.core = core;
            this.table = core.table;
            advance();
        }

        private void advance() {
            Node<K, V> n = upcoming == null ? null : upcoming.next;
            for (;;) {
                while (n == null) {
                    if (slot == table.length) {
                        upcoming = null;
                        upcomingValue = null;
                        return;
                    }
                    n = table[slot++];
                }
                V v = table == core.table ? n.value : core.get(n.key);
                if (v != null) {
                    upcoming = n;
                    upcomingValue = v;
                    return;
                }
                n = n.next;
            }
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
}
