package java.util.concurrent;

import java.util.function.Function;

/**
 * The table of {@link ConcurrentHashMap}. Readers take no lock: a resize builds a new table of new
 * nodes and publishes it in one write, so a chain a reader walks only ever loses a node, which
 * keeps its link and value. Writers hold this object's monitor, only for the table work: a key's
 * {@code equals}, {@code compareTo} and a value's {@code equals} are user code and may take locks, so
 * a writer runs them first, with nothing held, and then writes if what they looked at still stands.
 * A bin that gets long becomes a {@link BinTreap}, so colliding keys of an order stay cheap.
 *
 * <p>A computing function runs with no table lock, so other keys go on being read and written, and
 * the function may itself use the map. Instead the computation holds a {@link Claim} on its key: a
 * writer of that key blocks on the claim's monitor until it ends, as a {@code BLOCKED} thread. A
 * thread never waits for its own claim, so a recursive update of a key is only a lost update.
 */
final class HashCore<K, V> {
    static class Node<K, V> {
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

    /** What a long bin's slot holds instead of a chain; the root is its one changing part. */
    private static final class Bundle<K, V> extends Node<K, V> {
        BinTreap<K, V> root;

        Bundle(BinTreap<K, V> root) {
            super(0, null, null);
            this.root = root;
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

    /** What a writer learned with nothing held, and what {@link #current} needs to see it still stands. */
    private static final class Probe<K, V> {
        final Node<K, V>[] table;
        final int hash;
        final int slot;
        Node<K, V> found;
        V old;
        /** In a chain: its last node when the search passed it, and how long it was. */
        Node<K, V> last;
        int length;
        /** In a long bin: the root searched, the root to publish, and the entry that one adds. */
        BinTreap<K, V> root;
        BinTreap<K, V> changed;
        Node<K, V> entry;

        Probe(Node<K, V>[] table, int hash) {
            this.table = table;
            this.hash = hash;
            this.slot = hash & (table.length - 1);
        }
    }

    // What a write means to do in a long bin, which decides what its search has to build.
    private static final int FIND = 0;
    private static final int ADD = 1;
    private static final int CUT = 2;

    private static final int MIN_SLOTS = 16;
    private static final int MAX_SLOTS = 1 << 30;
    private static final int LONG_BIN = 8;

    /** Null until the first write, so that a large capacity costs nothing until it is used. */
    private Node<K, V>[] table;
    private final int firstSlots;
    private int size;
    private Claim claims;

    HashCore(int capacity) {
        int slots = MIN_SLOTS;
        while (slots < MAX_SLOTS && slots / 4 * 3 < capacity) {
            slots <<= 1;
        }
        firstSlots = slots;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V>[] newTable(int slots) {
        return (Node<K, V>[]) new Node<?, ?>[slots];
    }

    private synchronized Node<K, V>[] tableToWrite() {
        if (table == null) {
            table = newTable(firstSlots);
        }
        return table;
    }

    /**
     * Not observable through the map's results, but the order of an iteration follows from it. This
     * arithmetic keeps that order the one the oracle was seen to give, bin by bin (concurrent.md).
     */
    private static int mix(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    int size() {
        return size;
    }

    private Node<K, V> find(Object key, int hash) {
        Node<K, V>[] t = table;
        if (t == null) {
            return null;
        }
        Node<K, V> n = t[hash & (t.length - 1)];
        if (n instanceof Bundle) {
            return BinTreap.find(((Bundle<K, V>) n).root, hash, key);
        }
        for (; n != null; n = n.next) {
            if (n.hash == hash && (n.key == key || key.equals(n.key))) {
                return n;
            }
        }
        return null;
    }

    V get(Object key) {
        Node<K, V> n = find(key, mix(key));
        return n == null ? null : n.value;
    }

    boolean computing() {
        return claims != null;
    }

    private Claim claimOn(Claim first, Object key, int hash) {
        Thread me = Thread.currentThread();
        for (Claim c = first; c != null; c = c.next) {
            if (c.hash == hash && c.thread != me && (c.key == key || key.equals(c.key))) {
                return c;
            }
        }
        return null;
    }

    /** Waits out any computation of this key; answers the list of claims in which there was none. */
    private Claim awaitClaims(Object key, int hash) {
        for (;;) {
            Claim seen = claims;
            Claim held = seen == null ? null : claimOn(seen, key, hash);
            if (held == null) {
                return seen;
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

    /** The search behind every write. Runs user code, so it holds nothing. */
    private Probe<K, V> look(Object key, int hash, int aim) {
        Node<K, V>[] t = table != null ? table : tableToWrite();
        Probe<K, V> p = new Probe<>(t, hash);
        Node<K, V> n = t[p.slot];
        if (n instanceof Bundle) {
            lookInLongBin(p, ((Bundle<K, V>) n).root, key, hash, aim);
        } else {
            for (; n != null && p.found == null; n = n.next) {
                if (n.hash == hash && (n.key == key || key.equals(n.key))) {
                    p.found = n;
                } else {
                    p.last = n;
                    p.length++;
                }
            }
        }
        if (p.found != null) {
            p.old = p.found.value;
        }
        return p;
    }

    private void lookInLongBin(Probe<K, V> p, BinTreap<K, V> root, Object key, int hash, int aim) {
        p.root = root;
        if (aim == FIND) {
            p.found = BinTreap.find(root, hash, key);
            return;
        }
        @SuppressWarnings("unchecked")
        BinTreap.Descent<K, V> d = new BinTreap.Descent<>(hash, key, aim == ADD ? new Node<>(hash, (K) key, null) : null);
        p.changed = aim == ADD ? d.add(root) : d.cut(root);
        p.found = d.found;
        p.entry = d.entry;
    }

    /** With the monitor held: whether the search's answer is still true. */
    private boolean current(Probe<K, V> p) {
        if (p.table != table) {
            return false;
        }
        Node<K, V> n = p.table[p.slot];
        if (n instanceof Bundle) {
            return ((Bundle<K, V>) n).root == p.root;
        }
        if (p.root != null) {
            return false;
        }
        Node<K, V> last = null;
        for (; n != null; n = n.next) {
            if (n == p.found) {
                return true;
            }
            last = n;
        }
        // Keys only join at the end, so if that is unchanged nothing joined; what left does not matter.
        return p.found == null && last == p.last;
    }

    /** Applies a write of {@code value} (null removes) if the search still stands; else search again. */
    private synchronized boolean commit(Probe<K, V> p, Claim seen, K key, V value) {
        if (claims != seen || !current(p) || (p.found != null && p.found.value != p.old)) {
            return false;
        }
        if (p.found == null) {
            insert(p, key, value);
        } else if (value != null) {
            p.found.value = value;
        } else {
            unlink(p);
        }
        return true;
    }

    V put(K key, V value, boolean onlyIfAbsent) {
        int hash = mix(key);
        for (;;) {
            Claim seen = awaitClaims(key, hash);
            Probe<K, V> p = look(key, hash, ADD);
            if (p.found != null && onlyIfAbsent) {
                return p.old;
            }
            if (commit(p, seen, key, value)) {
                bundleIfLong(p, hash);
                return p.old;
            }
        }
    }

    /** Sets a present key to {@code update}, or removes it if that is null. Answers its old value. */
    V change(Object key, V update, Object expected) {
        int hash = mix(key);
        for (;;) {
            Claim seen = awaitClaims(key, hash);
            if (table == null) {
                return null;
            }
            Probe<K, V> p = look(key, hash, update == null ? CUT : FIND);
            if (p.found == null) {
                return null;
            }
            V old = p.old;
            if (expected != null && expected != old && !expected.equals(old)) {
                return null;
            }
            if (commit(p, seen, p.found.key, update)) {
                return old;
            }
        }
    }

    /**
     * The one way to compute: runs {@code step} on the value now mapped, alone among writers of
     * this key, and maps what it answers (or nothing, if null). A throwing step changes nothing.
     */
    V atomically(K key, Function<? super V, ? extends V> step) {
        int hash = mix(key);
        Claim mine = new Claim(hash, key);
        for (;;) {
            Claim seen = awaitClaims(key, hash);
            synchronized (mine) {
                if (stake(mine, seen)) {
                    try {
                        V fresh = step.apply(get(key));
                        store(key, hash, fresh);
                        return fresh;
                    } finally {
                        release(mine);
                    }
                }
            }
        }
    }

    /** Adds the claim, if the list is still the one found free of this key. */
    private synchronized boolean stake(Claim mine, Claim seen) {
        if (claims != seen) {
            return false;
        }
        mine.next = claims;
        claims = mine;
        return true;
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

    /** A computation's write: its claim keeps other writers of the key out, so none is waited for. */
    private void store(K key, int hash, V value) {
        if (value == null && table == null) {
            return;
        }
        for (;;) {
            Claim seen = claims;
            Probe<K, V> p = look(key, hash, value == null ? CUT : ADD);
            if (value == null && p.found == null) {
                return;
            }
            if (commit(p, seen, key, value)) {
                bundleIfLong(p, hash);
                return;
            }
        }
    }

    private void insert(Probe<K, V> p, K key, V value) {
        Node<K, V>[] t = p.table;
        if (p.root != null) {
            p.entry.value = value;
            ((Bundle<K, V>) t[p.slot]).root = p.changed;
        } else if (p.last == null) {
            t[p.slot] = new Node<>(p.hash, key, value);
        } else {
            p.last.next = new Node<>(p.hash, key, value);
        }
        size++;
        if (size > t.length / 4 * 3 && t.length < MAX_SLOTS) {
            grow(t);
        }
    }

    private void bundleIfLong(Probe<K, V> p, int hash) {
        if (p.found == null && p.root == null && p.length + 1 >= LONG_BIN) {
            bundle(hash);
        }
    }

    /**
     * Turns a long chain into a tree. The compares are user code, so they run on what the chain was,
     * and if it has changed since then the next write to it will try again. The nodes stay as they
     * are, links included, for whoever is walking that chain.
     */
    private void bundle(int hash) {
        Node<K, V>[] t = table;
        int slot = hash & (t.length - 1);
        Node<K, V> head = t[slot];
        if (head == null || head instanceof Bundle) {
            return;
        }
        int length = 0;
        Node<K, V> last = null;
        BinTreap<K, V> root = null;
        for (Node<K, V> n = head; n != null; n = n.next) {
            root = new BinTreap.Descent<>(n).add(root);
            last = n;
            length++;
        }
        synchronized (this) {
            if (t == table && t[slot] == head && chainIs(head, last, length)) {
                t[slot] = new Bundle<>(root);
            }
        }
    }

    /** Keys join only at the end, so a chain with the same ends and length has the same nodes. */
    private static <K, V> boolean chainIs(Node<K, V> head, Node<K, V> last, int length) {
        Node<K, V> end = null;
        int count = 0;
        for (Node<K, V> n = head; n != null; n = n.next) {
            end = n;
            count++;
        }
        return end == last && count == length;
    }

    /** New nodes too: a reader or iterator on the old table keeps a table that no longer changes. */
    private void grow(Node<K, V>[] old) {
        Node<K, V>[] t = newTable(old.length << 1);
        Node<K, V>[] tails = newTable(t.length);
        for (int i = 0; i < old.length; i++) {
            Node<K, V> n = old[i];
            if (n instanceof Bundle) {
                BinTreap<K, V> root = ((Bundle<K, V>) n).root;
                t[i] = bundled(BinTreap.half(root, old.length, 0));
                t[i + old.length] = bundled(BinTreap.half(root, old.length, old.length));
                continue;
            }
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

    private static <K, V> Node<K, V> bundled(BinTreap<K, V> root) {
        return root == null ? null : new Bundle<>(root);
    }

    private void unlink(Probe<K, V> p) {
        Node<K, V>[] t = p.table;
        Node<K, V> gone = p.found;
        Node<K, V> n = t[p.slot];
        if (n instanceof Bundle) {
            ((Bundle<K, V>) n).root = p.changed;
            if (p.changed == null) {
                t[p.slot] = null;
            }
        } else if (n == gone) {
            t[p.slot] = gone.next;
        } else {
            while (n.next != gone) {
                n = n.next;
            }
            n.next = gone.next;
        }
        size--;
    }

    synchronized void clear() {
        Node<K, V>[] t = table;
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                t[i] = null;
            }
        }
        size = 0;
    }

    /** The table as a walk starts on it, never null. */
    Node<K, V>[] tableToWalk() {
        Node<K, V>[] t = table;
        return t != null ? t : HashCore.<K, V>newTable(0);
    }

    /** Whether a walk is still on the current table. */
    boolean isCurrent(Node<K, V>[] t) {
        return t == table;
    }

    /** All the entries of a long bin, given what its slot holds; null for a chain. */
    static <K, V> Node<K, V>[] longBinEntries(Node<K, V> slotted) {
        return slotted instanceof Bundle ? ((Bundle<K, V>) slotted).root.entries() : null;
    }
}
