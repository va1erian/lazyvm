package java.util.concurrent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
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
 * the function may itself use the map. Instead the computation holds a claim on its key ({@link HashClaims}): a
 * writer of that key blocks on the claim's monitor until it ends, as a {@code BLOCKED} thread. Claims
 * go in a list, newest first, and each waits only for those older than itself, so no number of later
 * ones can hold it up. A plain writer that finds any claim in the list makes one too, and that is
 * its way past them. A thread never waits for its own claim, so a recursive update of a key is only a
 * lost update.
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

    /** What a writer learned with nothing held, and what {@link #current} needs to see it still stands. */
    private static final class Probe<K, V> {
        final Object[] table;
        final int hash;
        final int slot;
        Node<K, V> found;
        V old;
        /** The write depends on {@code old} being still the value, as a conditional one does. */
        boolean exact;
        /** In a chain: its last node when the search passed it, and how long it was. */
        Node<K, V> last;
        int length;
        /** In a long bin: the root searched, the root to publish, and the entry that one adds. */
        BinTreap<K, V> root;
        BinTreap<K, V> changed;
        Node<K, V> entry;
        /** What user code said in this write's earlier searches of a long bin. None in the first. */
        IdentityHashMap<Node<K, V>, Integer> told;

        Probe(Object[] table, int hash, Probe<K, V> before) {
            this.table = table;
            this.hash = hash;
            this.slot = hash & (table.length - 1);
            if (before != null) {
                told = before.told != null ? before.told : new IdentityHashMap<>();
            }
        }
    }

    // What a write means to do in a long bin, which decides what its search has to build.
    private static final int FIND = 0;
    private static final int ADD = 1;
    private static final int CUT = 2;

    private static final int MIN_SLOTS = 16;
    private static final int MAX_SLOTS = 1 << 30;
    private static final int LONG_BIN = 16;

    /** Null until the first write, so that a large capacity costs nothing until it is used. */
    private Object[] table;
    private final int firstSlots;
    private int size;
    /** Written with this object's monitor held, so that a commit sees them and its bin together. */
    private final HashClaims claims = new HashClaims();

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

    private Node<K, V> find(Object key, int hash) {
        Object[] t = table;
        if (t == null) {
            return null;
        }
        Object slot = t[hash & (t.length - 1)];
        if (slot instanceof BinTreap) {
            return BinTreap.find(HashCore.<K, V>tree(slot), hash, key, null);
        }
        for (Node<K, V> n = chain(slot); n != null; n = n.next) {
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
        return claims.any();
    }

    /**
     * The search behind every write. Runs user code, so it holds nothing. A write searching again,
     * after {@code before} lost to another write, asks user code only about what is new to it, so
     * that the search gets shorter and fits between those writes, however long the code takes.
     */
    private Probe<K, V> look(Object key, int hash, int aim, Probe<K, V> before) {
        Object[] t = table != null ? table : tableToWrite();
        Probe<K, V> p = new Probe<>(t, hash, before);
        Object slot = t[p.slot];
        if (slot instanceof BinTreap) {
            lookInLongBin(p, HashCore.<K, V>tree(slot), key, hash, aim);
        } else {
            for (Node<K, V> n = chain(slot); n != null && p.found == null; n = n.next) {
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
            p.found = BinTreap.find(root, hash, key, p.told);
            return;
        }
        @SuppressWarnings("unchecked")
        BinTreap.Descent<K, V> d = new BinTreap.Descent<>(hash, key, aim == ADD ? new Node<>(hash, (K) key, null) : null, p.told);
        p.changed = aim == ADD ? d.add(root) : d.cut(root);
        p.found = d.found;
        p.entry = d.entry;
    }

    /**
     * With the monitor held: whether the search's answer is still true. It only asks what could have
     * changed the answer, so writes of other keys in the bin do not send a search back to the start.
     */
    private boolean current(Probe<K, V> p) {
        if (p.table != table) {
            return false;
        }
        Object slot = p.table[p.slot];
        if (slot instanceof BinTreap || p.root != null) {
            return slot == p.root;
        }
        Node<K, V> n = chain(slot);
        if (p.found != null) {
            while (n != null && n != p.found) {
                n = n.next;
            }
            return n != null;
        }
        return nothingNewOfHash(p, n);
    }

    /**
     * Keys only join at the end, so what lies behind the search's last node is new, and only a new
     * node of this hash could be the key. Leaves the chain's real end in {@code p.last} for the insert.
     */
    private boolean nothingNewOfHash(Probe<K, V> p, Node<K, V> first) {
        boolean behind = p.last == null;
        Node<K, V> end = null;
        for (Node<K, V> n = first; n != null; n = n.next) {
            if (behind && n.hash == p.hash) {
                return false;
            }
            behind |= n == p.last;
            end = n;
        }
        p.last = end;
        return behind;
    }

    /**
     * Applies a write of {@code value} (null removes) if the search still stands; else search again.
     * With {@code watch}, also if no computation that might be of this key has started since the
     * search, as the writer has made no claim for it to see.
     */
    private synchronized boolean commit(Probe<K, V> p, K key, V value, boolean watch) {
        if ((watch && claims.mayHold(p.hash)) || !current(p) || (p.exact && p.found.value != p.old)) {
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
        Probe<K, V> p = null;
        while (!claims.any()) {
            p = look(key, hash, ADD, p);
            if (p.found != null && onlyIfAbsent) {
                return p.old;
            }
            if (commit(p, key, value, true)) {
                treeIfLong(p, hash);
                return p.old;
            }
        }
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
        Probe<K, V> p = null;
        while (!claims.any()) {
            if (table == null) {
                return null;
            }
            p = look(key, hash, update == null ? CUT : FIND, p);
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
        HashClaims.Claim mine = new HashClaims.Claim(hash, key);
        synchronized (mine) {
            stake(mine);
            try {
                mine.awaitOlder();
                mine.wiped = false;
                V old = get(key);
                V fresh = step.apply(old);
                // A clear since then took the entry with it. So this ran before the clear: its answer
                // stands, and goes nowhere. Finding no entry, it can as well have run after.
                if (old == null || !mine.wiped) {
                    store(key, hash, fresh);
                }
                return fresh;
            } finally {
                release(mine);
            }
        }
    }

    private synchronized void stake(HashClaims.Claim mine) {
        claims.add(mine);
    }

    private synchronized void release(HashClaims.Claim mine) {
        claims.remove(mine);
    }

    /** A computation's write: its claim keeps other writers of the key out, so none is waited for. */
    private void store(K key, int hash, V value) {
        if (value == null && table == null) {
            return;
        }
        Probe<K, V> p = null;
        for (;;) {
            p = look(key, hash, value == null ? CUT : ADD, p);
            if (value == null && p.found == null) {
                return;
            }
            if (commit(p, key, value, false)) {
                treeIfLong(p, hash);
                return;
            }
        }
    }

    private void insert(Probe<K, V> p, K key, V value) {
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

    private void treeIfLong(Probe<K, V> p, int hash) {
        if (p.found == null && p.root == null && p.length + 1 >= LONG_BIN) {
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
            for (Node<K, V> n = chain(slot); n != null; n = n.next) {
                int to = n.hash & (t.length - 1);
                Node<K, V> copy = new Node<>(n.hash, n.key, n.value);
                if (tails[to] == null) {
                    t[to] = copy;
                } else {
                    HashCore.<K, V>chain(tails[to]).next = copy;
                }
                tails[to] = copy;
            }
        }
        table = t;
    }

    private void unlink(Probe<K, V> p) {
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
     * those it wipes under count as having run before it, see {@link #atomically}.
     */
    void clear() {
        claims.awaitOthers();
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
        claims.markWiped();
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
