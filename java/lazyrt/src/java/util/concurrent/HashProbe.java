package java.util.concurrent;

import java.util.IdentityHashMap;

/**
 * The search behind every write of a {@link HashCore}: what a writer learned with nothing held, and
 * what is needed to see later that it still stands. It runs user code, so it holds nothing. A write
 * searching again, after an earlier search lost to another write, asks user code only about what is new
 * to it, so that the search gets shorter and fits between those writes, however long the code takes.
 */
final class HashProbe<K, V> {
    // What a write means to do in a long bin, which decides what its search has to build.
    static final int FIND = 0;
    static final int ADD = 1;
    static final int CUT = 2;

    final Object[] table;
    final int hash;
    final int slot;
    HashCore.Node<K, V> found;
    V old;
    /** The write depends on {@code old} being still the value, as a conditional one does. */
    boolean exact;
    /** In a chain: its last node when the search passed it, and how long it was. */
    HashCore.Node<K, V> last;
    int length;
    /** In a long bin: the root searched, the root to publish, and the entry that one adds. */
    BinTreap<K, V> root;
    BinTreap<K, V> changed;
    HashCore.Node<K, V> entry;
    /** If not 0: the write holds only as long as {@code clear} has run just this many times. */
    long era;
    /** What user code said in this write's earlier searches of a long bin. None in the first. */
    private IdentityHashMap<HashCore.Node<K, V>, Integer> told;

    @SuppressWarnings("unchecked")
    HashProbe(Object[] table, Object key, int hash, int aim, HashProbe<K, V> before) {
        this.table = table;
        this.hash = hash;
        this.slot = hash & (table.length - 1);
        if (before != null) {
            told = before.told != null ? before.told : new IdentityHashMap<>();
        }
        Object s = table[slot];
        if (s instanceof BinTreap) {
            searchLongBin((BinTreap<K, V>) s, key, aim);
        } else {
            for (HashCore.Node<K, V> n = (HashCore.Node<K, V>) s; n != null && found == null; n = n.next) {
                if (n.hash == hash && (n.key == key || key.equals(n.key))) {
                    found = n;
                } else {
                    last = n;
                    length++;
                }
            }
        }
        if (found != null) {
            old = found.value;
        }
    }

    @SuppressWarnings("unchecked")
    private void searchLongBin(BinTreap<K, V> tree, Object key, int aim) {
        root = tree;
        if (aim == FIND) {
            found = BinTreap.find(tree, hash, key, told);
            return;
        }
        BinTreap.Descent<K, V> d = new BinTreap.Descent<>(hash, key, aim == ADD ? new HashCore.Node<>(hash, (K) key, null) : null, told);
        changed = aim == ADD ? d.add(tree) : d.cut(tree);
        found = d.found;
        entry = d.entry;
    }

    /**
     * With the core's monitor held: whether the answer is still true. It only asks what could have
     * changed the answer, so writes of other keys in the bin do not send a search back to the start.
     */
    @SuppressWarnings("unchecked")
    boolean stands(Object[] now) {
        if (table != now) {
            return false;
        }
        Object s = table[slot];
        if (s instanceof BinTreap || root != null) {
            return s == root;
        }
        HashCore.Node<K, V> n = (HashCore.Node<K, V>) s;
        if (found != null) {
            while (n != null && n != found) {
                n = n.next;
            }
            return n != null;
        }
        return nothingNewOfHash(n);
    }

    /**
     * Keys only join at the end, so what lies behind the search's last node is new, and only a new
     * node of this hash could be the key. Leaves the chain's real end in {@code last} for the insert.
     */
    private boolean nothingNewOfHash(HashCore.Node<K, V> first) {
        boolean behind = last == null;
        HashCore.Node<K, V> end = null;
        for (HashCore.Node<K, V> n = first; n != null; n = n.next) {
            if (behind && n.hash == hash) {
                return false;
            }
            behind |= n == last;
            end = n;
        }
        last = end;
        return behind;
    }
}
