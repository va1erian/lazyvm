package java.util;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * HashMap bins that hold a red-black tree besides their sequence (spec 1.H6 to H12). A tree
 * bin's sequence is still table[i] linked through next; roots[i] is its tree's root.
 */
final class HashTree {
    // Spec 1.H12: a split half this small becomes a list bin.
    static final int LIST_AT_SPLIT = 6;

    /** The last class asked about in qualifies(), with the answer. */
    private static volatile Object[] lastAnswer = {null, Boolean.FALSE};

    private HashTree() {
    }

    // Spec 1.H6: tree order of key k (mixed hash h) against node p; negative means k goes left.
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int order(int h, Object k, HashMap.Cell<?, ?> p) {
        if (h != p.hash) {
            return h < p.hash ? -1 : 1;
        }
        Object pk = p.key;
        if (k != null && pk != null) {
            Class<?> c = k.getClass();
            if (c == pk.getClass() && qualifies(c)) {
                int r = ((Comparable) k).compareTo(pk);
                if (r != 0) {
                    return r;
                }
            }
            int r = c.getName().compareTo(pk.getClass().getName());
            if (r != 0) {
                return r;
            }
        }
        return System.identityHashCode(k) <= System.identityHashCode(pk) ? -1 : 1;
    }

    // Spec 1.H6: String, or a class that itself declares Comparable<itself>.
    private static boolean qualifies(Class<?> c) {
        if (c == String.class) {
            return true;
        }
        Object[] last = lastAnswer;
        if (last[0] == c) {
            return (Boolean) last[1];
        }
        boolean yes = false;
        for (Type t : c.getGenericInterfaces()) {
            if (t instanceof ParameterizedType) {
                ParameterizedType p = (ParameterizedType) t;
                Type[] args = p.getActualTypeArguments();
                if (p.getRawType() == Comparable.class && args.length == 1 && args[0] == c) {
                    yes = true;
                }
            }
        }
        lastAnswer = new Object[] {c, yes};
        return yes;
    }

    /** Spec 1.H7: makes bin i a tree by inserting its sequence in order; the root goes first. */
    static <K, V> void growTree(HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i) {
        HashMap.Cell<K, V> root = null;
        for (HashMap.Cell<K, V> n = table[i]; n != null; n = n.next) {
            n.unlinkTree();
            root = root == null ? blackRoot(n) : plant(root, n);
        }
        roots[i] = root;
        rootFirst(table, i, root);
    }

    private static <K, V> HashMap.Cell<K, V> blackRoot(HashMap.Cell<K, V> n) {
        n.red = false;
        return n;
    }

    /** Attaches n where tree order leads, rebalances, and returns the new root. */
    private static <K, V> HashMap.Cell<K, V> plant(HashMap.Cell<K, V> root, HashMap.Cell<K, V> n) {
        attach(root, n);
        return (HashMap.Cell<K, V>) RedBlack.fixInsert(root, n);
    }

    /** Hangs n at the end of its tree-order path (one comparison per node passed) and returns its parent. */
    private static <K, V> HashMap.Cell<K, V> attach(HashMap.Cell<K, V> root, HashMap.Cell<K, V> n) {
        HashMap.Cell<K, V> p = root;
        while (true) {
            boolean left = order(n.hash, n.key, p) < 0;
            MapNode<K, V> next = left ? p.left : p.right;
            if (next == null) {
                RedBlack.attach(p, n, left);
                return p;
            }
            p = (HashMap.Cell<K, V>) next;
        }
    }

    // Spec 1.H8: a new key goes into the tree and, in the sequence, right after its parent.
    static <K, V> void insert(HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i, HashMap.Cell<K, V> n) {
        HashMap.Cell<K, V> root = roots[i];
        HashMap.Cell<K, V> p = attach(root, n);
        n.next = p.next;
        p.next = n;
        root = (HashMap.Cell<K, V>) RedBlack.fixInsert(root, n);
        roots[i] = root;
        rootFirst(table, i, root);
    }

    /**
     * Spec 1.H9 (map-style) and H10 (iterator-style): n leaves the sequence; a map-style removal
     * from a tree that is too thin reverts the bin to a list, otherwise n leaves the tree.
     */
    static <K, V> void remove(HashMap<K, V> owner, HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i,
            HashMap.Cell<K, V> n, boolean mapStyle) {
        unlinkFromSequence(table, i, n);
        HashMap.Cell<K, V> root = roots[i];
        if (mapStyle && (root.left == null || root.right == null || root.left.left == null)) {
            roots[i] = null;
            renew(owner, table, i);
            n.unlinkTree();
            return;
        }
        root = (HashMap.Cell<K, V>) RedBlack.remove(root, n);
        roots[i] = root;
        if (table[i] == null) {
            roots[i] = null;
        } else if (mapStyle) {
            rootFirst(table, i, root);
        }
    }

    /**
     * Spec 1.H12: the tree bin j of the old table splits by bit oldN; a small half becomes a list,
     * a large half a new tree, and a bin that stays whole keeps its tree and sequence.
     */
    static <K, V> void divide(HashMap<K, V> owner, HashMap.Cell<K, V> bin, HashMap.Cell<K, V> root, int j, int oldN,
            HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots) {
        HashMap.Cell<K, V>[] halves = halves(bin, oldN);
        int loCount = count(halves[0]);
        int hiCount = count(halves[1]);
        place(owner, halves[0], loCount, hiCount, root, j, table, roots);
        place(owner, halves[1], hiCount, loCount, root, j + oldN, table, roots);
    }

    // Spec 1.H5: on growth a bin's entries keep their order, split by bit `bit` of the hash.
    static <K, V> HashMap.Cell<K, V>[] halves(HashMap.Cell<K, V> head, int bit) {
        HashMap.Cell<K, V>[] heads = HashMap.newTable(2);
        HashMap.Cell<K, V>[] tails = HashMap.newTable(2);
        for (HashMap.Cell<K, V> e = head; e != null;) {
            HashMap.Cell<K, V> next = e.next;
            int half = (e.hash & bit) == 0 ? 0 : 1;
            e.next = null;
            if (tails[half] == null) {
                heads[half] = e;
            } else {
                tails[half].next = e;
            }
            tails[half] = e;
            e = next;
        }
        return heads;
    }

    private static <K, V> void place(HashMap<K, V> owner, HashMap.Cell<K, V> head, int count, int otherCount,
            HashMap.Cell<K, V> root, int i, HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots) {
        if (head == null) {
            return;
        }
        table[i] = head;
        if (count <= LIST_AT_SPLIT) {
            renew(owner, table, i);
        } else if (otherCount == 0) {
            roots[i] = root;
        } else {
            growTree(table, roots, i);
        }
    }

    /** Spec 1.H7: bin i becomes a tree of new entries; returns the roots table, made if needed. */
    static <K, V> HashMap.Cell<K, V>[] plant(HashMap<K, V> owner, HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i) {
        HashMap.Cell<K, V>[] r = roots == null ? HashMap.newTable(table.length) : roots;
        renew(owner, table, i);
        growTree(table, r, i);
        return r;
    }

    /**
     * Spec 1.H15: when bin i turns from a list into a tree or back, its entries are replaced by new
     * ones in the same order; the old ones keep their links, so a walk standing on one goes on
     * along the bin as it was, and an entry held from before no longer writes through.
     */
    static <K, V> void renew(HashMap<K, V> owner, HashMap.Cell<K, V>[] t, int i) {
        HashMap.Cell<K, V> last = null;
        for (HashMap.Cell<K, V> e = t[i]; e != null; e = e.next) {
            HashMap.Cell<K, V> c = owner.replica(e);
            if (last == null) {
                t[i] = c;
            } else {
                last.next = c;
            }
            last = c;
        }
    }

    private static int count(HashMap.Cell<?, ?> head) {
        int n = 0;
        for (; head != null; head = head.next) {
            n++;
        }
        return n;
    }

    /** Moves `root` to the front of bin i's sequence. */
    static <K, V> void rootFirst(HashMap.Cell<K, V>[] table, int i, HashMap.Cell<K, V> root) {
        if (table[i] != root) {
            unlinkFromSequence(table, i, root);
            root.next = table[i];
            table[i] = root;
        }
    }

    static <K, V> void unlinkFromSequence(HashMap.Cell<K, V>[] table, int i, HashMap.Cell<K, V> n) {
        if (table[i] == n) {
            table[i] = n.next;
        } else {
            HashMap.Cell<K, V> p = table[i];
            while (p.next != n) {
                p = p.next;
            }
            p.next = n.next;
        }
    }
}
