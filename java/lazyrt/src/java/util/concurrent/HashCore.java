package java.util.concurrent;

import java.util.ArrayList;
import java.util.function.Function;

/**
 * The table of {@link ConcurrentHashMap}. A slot holds nothing, the first node of a chain, or the
 * root of a {@link BinTreap}, so what a reader reads from a slot is all it needs. Readers take no
 * lock: a resize builds a new table of new nodes and publishes it in one write, so a chain a reader
 * walks only ever loses a node, which keeps its link and value. Writers hold this object's monitor,
 * only for the table work: a key's {@code equals}, {@code compareTo} and a value's {@code equals} are
 * user code and may take locks, so a writer runs them first, with nothing held, and then writes if
 * what they looked at still stands. A bin that gets long becomes a tree, so colliding keys of an order
 * stay cheap.
 *
 * <p>A computing function runs with no table lock, so other keys go on being read and written, and
 * the function may itself use the map. Instead it runs in a claim on its key ({@link HashClaims}), and
 * a write outside any claim lands only if, at that moment and under this monitor, no claim of its hash
 * is listed; else it runs in a claim too. So what a write costs depends only on what it collides with.
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

    private static final int MIN_SLOTS = 16;
    private static final int MAX_SLOTS = 1 << 30;
    private static final int LONG_BIN = 16;
    private static final long ANY_ERA = 0;

    /** Null until the first write, so that a large capacity costs nothing until it is used. */
    private Object[] table;
    private final int firstSlots;
    private int size;
    /** How many {@code clear}s there were, from 1: a probe's era of 0 is {@link #ANY_ERA}. */
    private long wipes = 1;
    /** Written with this object's monitor held, so that a commit sees them and its bin together. */
    private final HashClaims claims = new HashClaims(this);

    HashCore(int capacity) {
        int slots = MIN_SLOTS;
        while (slots < MAX_SLOTS && slots / 4 * 3 < capacity) {
            slots <<= 1;
        }
        firstSlots = slots;
    }

    private synchronized Object[] tableToWrite() {
        if (table == null) {
            table = new Object[firstSlots];
        }
        return table;
    }

    /**
     * What was observed: with this mix, and with no other tried, a map that has not grown iterates in
     * the oracle's order, on all 300 small key sets of a probe (concurrent.md G7, `CollectionPins`).
     */
    private static int mix(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    int size() {
        return size;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Node<K, V> chain(Object slot) {
        return (Node<K, V>) slot;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> BinTreap<K, V> tree(Object slot) {
        return (BinTreap<K, V>) slot;
    }

    @SuppressWarnings("unchecked")
    private Node<K, V> find(Object key, int hash) {
        Object[] t = table;
        if (t == null) {
            return null;
        }
        Object slot = t[hash & (t.length - 1)];
        if (slot instanceof BinTreap) {
            return BinTreap.find(HashCore.<K, V>tree(slot), hash, key, null);
        }
        // Cast in place: on these paths a call is a tenth of the whole.
        for (Node<K, V> n = (Node<K, V>) slot; n != null; n = n.next) {
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

    /** The value now mapped, for a node of a table the map has left: its stored hash spares a hashCode call. */
    V valueNow(Node<K, V> old) {
        Node<K, V> n = find(old.key, old.hash);
        return n == null ? null : n.value;
    }

    /** The mapped value, or null also if a write to the key may be under way, which only a claim can wait for. */
    V getUnclaimed(Object key) {
        int hash = mix(key);
        if (claims.count != 0 && claims.mayHold(hash)) {
            return null;
        }
        Node<K, V> n = find(key, hash);
        return n == null ? null : n.value;
    }

    private HashProbe<K, V> look(Object key, int hash, int aim, HashProbe<K, V> before) {
        return new HashProbe<>(table != null ? table : tableToWrite(), key, hash, aim, before);
    }

    /**
     * Applies a write of {@code value} (null removes) if the search still stands; else search again.
     * With {@code watch}, also if no claim that might be on this key is listed, as the writer has none.
     * A write outdated by a {@code clear} goes nowhere, and has nothing more to do.
     */
    private synchronized boolean commit(HashProbe<K, V> p, K key, V value, boolean watch) {
        if (p.era != ANY_ERA && p.era != wipes) {
            return true;
        }
        if ((watch && claims.count != 0 && claims.mayHold(p.hash)) || !p.stands(table) || (p.exact && p.found.value != p.old)) {
            return false;
        }
        if (p.found == null) {
            insert(p, key, value);
            return true;
        }
        p.old = p.found.value;
        if (value != null) {
            p.found.value = value;
        } else {
            unlink(p);
        }
        return true;
    }

    V put(K key, V value, boolean onlyIfAbsent) {
        int hash = mix(key);
        HashProbe<K, V> p = null;
        do {
            p = look(key, hash, HashProbe.ADD, p);
            if (p.found != null && onlyIfAbsent) {
                if (claims.count != 0 && claims.mayHold(hash)) {
                    break;
                }
                return p.old;
            }
            if (commit(p, key, value, true)) {
                if (p.found == null) {
                    treeIfLong(p, hash);
                }
                return p.old;
            }
        } while (claims.count == 0 || !claims.mayHold(hash));
        Object[] was = new Object[1];
        atomically(key, old -> {
            was[0] = old;
            return old != null && onlyIfAbsent ? old : value;
        });
        return HashCore.<V>value(was);
    }

    @SuppressWarnings("unchecked")
    private static <V> V value(Object[] holder) {
        return (V) holder[0];
    }

    /** Sets a present key to {@code update}, or removes it if that is null. Answers its old value. */
    @SuppressWarnings("unchecked")
    V change(Object key, V update, Object expected) {
        int hash = mix(key);
        HashProbe<K, V> p = null;
        while (claims.count == 0 || !claims.mayHold(hash)) {
            if (table == null) {
                return null;
            }
            p = look(key, hash, update == null ? HashProbe.CUT : HashProbe.FIND, p);
            if (p.found == null) {
                return null;
            }
            if (expected != null && !matches(expected, p.old)) {
                return null;
            }
            p.exact = expected != null;
            if (commit(p, p.found.key, update, true)) {
                return p.old;
            }
            // Lost to a write. Asking the value's equals again could go on as long as that writer does.
            if (expected != null) {
                break;
            }
        }
        Object[] was = new Object[1];
        atomically((K) key, old -> {
            if (old == null || (expected != null && !matches(expected, old))) {
                return old;
            }
            was[0] = old;
            return update;
        });
        return HashCore.<V>value(was);
    }

    private static boolean matches(Object expected, Object value) {
        return expected == value || expected.equals(value);
    }

    /**
     * The one way to compute: runs {@code step} on the value now mapped, alone among writers of
     * this key, and maps what it answers (or nothing, if null). A throwing step changes nothing.
     */
    V atomically(K key, Function<? super V, ? extends V> step) {
        int hash = mix(key);
        for (;;) {
            HashClaims.Claim mine = new HashClaims.Claim(hash, key);
            HashClaims.Claim other;
            synchronized (mine) {
                other = claims.stake(mine);
                if (other == null || other == mine) {
                    try {
                        other = other == null ? null : claims.joined(mine);
                        if (other == null) {
                            mine.open = true;
                            return run(key, hash, step);
                        }
                    } finally {
                        // Still itself: a key's equals threw before anything was found.
                        if (other == null || other == mine) {
                            claims.leave(mine);
                        }
                    }
                }
            }
            synchronized (other) {
                try {
                    // Not open: the thread that listed it gave it up as well, so look again.
                    if (other.open) {
                        return run(key, hash, step);
                    }
                } finally {
                    claims.leave(other);
                }
            }
        }
    }

    private V run(K key, int hash, Function<? super V, ? extends V> step) {
        long era = wipes;
        HashProbe<K, V> p = table == null ? null : look(key, hash, HashProbe.FIND, null);
        V old = p == null ? null : p.old;
        V fresh = step.apply(old);
        // A clear since then took the entry with it. So this ran before the clear: its answer
        // stands, and goes nowhere. Finding no entry, it can as well have run after.
        store(key, hash, fresh, p, old == null ? ANY_ERA : era);
        return fresh;
    }

    /**
     * A computation's write: its claim keeps other writers of the key out, so none is waited for.
     * What {@code p} found in a chain serves any write; in a long bin that search built no change.
     */
    private void store(K key, int hash, V value, HashProbe<K, V> p, long era) {
        if (value == null && table == null) {
            return;
        }
        boolean search = p == null || p.root != null;
        for (;;) {
            if (search) {
                p = look(key, hash, value == null ? HashProbe.CUT : HashProbe.ADD, p);
            }
            p.era = era;
            if (value == null && p.found == null) {
                return;
            }
            if (commit(p, key, value, false)) {
                if (p.found == null) {
                    treeIfLong(p, hash);
                }
                return;
            }
            search = true;
        }
    }

    private void insert(HashProbe<K, V> p, K key, V value) {
        Object[] t = p.table;
        if (p.root != null) {
            p.entry.value = value;
            t[p.slot] = p.changed;
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

    private void treeIfLong(HashProbe<K, V> p, int hash) {
        if (p.root == null && p.length + 1 >= LONG_BIN) {
            toTree(hash);
        }
    }

    /**
     * Turns a long chain into a tree. The compares are user code, so they run on what the chain was,
     * and if it has changed since then the next write to it will try again. The tree gets new nodes:
     * whoever walks the old chain still can, but once nobody does, nothing keeps what left it alive.
     */
    private void toTree(int hash) {
        Object[] t = table;
        int slot = hash & (t.length - 1);
        Object head = t[slot];
        if (head == null || head instanceof BinTreap) {
            return;
        }
        ArrayList<Node<K, V>> copies = new ArrayList<>();
        BinTreap<K, V> root = BinTreap.ofChain(HashCore.<K, V>chain(head), copies);
        synchronized (this) {
            if (t == table && t[slot] == head && BinTreap.takeValues(HashCore.<K, V>chain(head), copies)) {
                t[slot] = root;
            }
        }
    }

    /** New nodes too: a reader or iterator on the old table keeps a table that no longer changes. */
    @SuppressWarnings("unchecked")
    private void grow(Object[] old) {
        Object[] t = new Object[old.length << 1];
        Object[] tails = new Object[t.length];
        for (int i = 0; i < old.length; i++) {
            Object slot = old[i];
            if (slot instanceof BinTreap) {
                BinTreap<K, V> root = tree(slot);
                t[i] = BinTreap.half(root, old.length, 0);
                t[i + old.length] = BinTreap.half(root, old.length, old.length);
                continue;
            }
            for (Node<K, V> n = (Node<K, V>) slot; n != null; n = n.next) {
                int to = n.hash & (t.length - 1);
                Node<K, V> copy = new Node<>(n.hash, n.key, n.value);
                if (tails[to] == null) {
                    t[to] = copy;
                } else {
                    ((Node<K, V>) tails[to]).next = copy;
                }
                tails[to] = copy;
            }
        }
        table = t;
    }

    private void unlink(HashProbe<K, V> p) {
        Object[] t = p.table;
        Node<K, V> gone = p.found;
        Object slot = t[p.slot];
        if (slot instanceof BinTreap) {
            t[p.slot] = p.changed;
        } else if (slot == gone) {
            t[p.slot] = gone.next;
        } else {
            Node<K, V> n = chain(slot);
            while (n.next != gone) {
                n = n.next;
            }
            n.next = gone.next;
        }
        size--;
    }

    /**
     * Waits out the computations running now, but not those that start later, or it might never end:
     * those it wipes under count as having run before it, see {@link #run}.
     */
    void clear() {
        claims.awaitRunning();
        wipe();
    }

    private synchronized void wipe() {
        Object[] t = table;
        if (t != null) {
            for (int i = 0; i < t.length; i++) {
                t[i] = null;
            }
        }
        size = 0;
        wipes++;
    }

    /** The table as a walk starts on it, never null. */
    Object[] tableToWalk() {
        Object[] t = table;
        return t != null ? t : new Object[0];
    }

    /** Whether a walk is still on the current table. */
    boolean isCurrent(Object[] t) {
        return t == table;
    }
}
