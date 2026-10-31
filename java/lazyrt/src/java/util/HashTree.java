package java.util;

import lazy.internal.KeyOrder;

/**
 * HashMap bins that hold a red-black tree besides their sequence (spec 1.H6 to H12). A tree
 * bin's sequence is still table[i] linked through next; roots[i] is its tree's root.
 */
final class HashTree {
    // Spec 1.H12: a split half this small becomes a list bin.
    static final int LIST_AT_SPLIT = 6;

    private HashTree() {
    }

    /**
     * Spec 1.H6, section 12: the entry for key k in the tree under p. It goes down by mixed hash and,
     * for two keys of one qualifying class, by compareTo; where neither decides (equal hashes and
     * no usable or a zero compareTo), it searches the right side and then goes on to the left.
     * Keys of one qualifying class are so found in logarithmic time, which tree bins are for.
     */
    static <K, V> HashMap.Cell<K, V> lookup(HashMap.Cell<K, V> first, HashMap.Cell<K, V> root, int h, Object k) {
        return isFirst(first, h, k) ? first : lookup(root, h, k);
    }

    // Spec 1.H8: a search of a tree bin tries the first entry of its sequence before the tree.
    private static boolean isFirst(HashMap.Cell<?, ?> first, int h, Object k) {
        return first.hash == h && Objects.equals(k, first.key);
    }

    private static <K, V> HashMap.Cell<K, V> lookup(HashMap.Cell<K, V> p, int h, Object k) {
        while (p != null) {
            if (h != p.hash) {
                p = child(p, h < p.hash);
                continue;
            }
            if (Objects.equals(k, p.key)) {
                return p;
            }
            // Spec 12: with only one child there is one way on, and compareTo is not asked.
            if (p.left == null || p.right == null) {
                p = child(p, p.right == null);
                continue;
            }
            // Spec 1.H6 step 2.
            int r = KeyOrder.compared(k, p.key);
            if (r != 0) {
                p = child(p, r < 0);
                continue;
            }
            HashMap.Cell<K, V> q = lookup(child(p, false), h, k);
            if (q != null) {
                return q;
            }
            p = child(p, true);
        }
        return null;
    }

    /**
     * Spec 1.H8: put-style insertion of k into tree bin i in a single descent, the one that places
     * a new key (so it makes H6's compareTo calls and no more): returns the entry already holding k,
     * or null once a new entry for k and v hangs where the descent ended.
     */
    static <K, V> HashMap.Cell<K, V> putIn(HashMap<K, V> owner, HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots,
            int i, int h, K k, V v) {
        if (isFirst(table[i], h, k)) {
            return table[i];
        }
        Spot<K, V> s = seek(roots[i], h, k, true);
        if (s.found != null) {
            return s.found;
        }
        HashMap.Cell<K, V> n = owner.makeCell(h, k, v);
        owner.modCount++;
        RedBlack.attach(s.parent, n, s.left);
        hangAfter(table, roots, i, s.parent, n);
        return null;
    }

    /** Where a descent ended: the entry already holding the key, or the parent and side for a new one. */
    private static final class Spot<K, V> {
        HashMap.Cell<K, V> found;
        HashMap.Cell<K, V> parent;
        boolean left;
    }

    /**
     * Spec 1.H6, H8: the one descent by tree order from root for key k (mixed hash h), one compareTo
     * per node passed where H6 asks for it. With `find`, a node holding k ends it first, and at the
     * first node where compareTo cannot decide both sides are searched for k once.
     */
    private static <K, V> Spot<K, V> seek(HashMap.Cell<K, V> root, int h, Object k, boolean find) {
        Spot<K, V> s = new Spot<K, V>();
        HashMap.Cell<K, V> p = root;
        boolean searched = !find;
        while (true) {
            int r;
            if (h != p.hash) {
                r = h < p.hash ? -1 : 1;
            } else if (find && Objects.equals(k, p.key)) {
                s.found = p;
                return s;
            } else if ((r = KeyOrder.compared(k, p.key)) == 0) {
                if (!searched) {
                    searched = true;
                    HashMap.Cell<K, V> q = lookup(child(p, true), h, k);
                    s.found = q != null ? q : lookup(child(p, false), h, k);
                    if (s.found != null) {
                        return s;
                    }
                }
                // Spec 1.H6 steps 3 and 4.
                r = KeyOrder.anySide(k, p.key);
            }
            HashMap.Cell<K, V> next = child(p, r < 0);
            if (next == null) {
                s.parent = p;
                s.left = r < 0;
                return s;
            }
            p = next;
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> HashMap.Cell<K, V> child(HashMap.Cell<K, V> p, boolean left) {
        return (HashMap.Cell<K, V>) (left ? p.left : p.right);
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
        Spot<K, V> s = seek(root, n.hash, n.key, false);
        RedBlack.attach(s.parent, n, s.left);
        return s.parent;
    }

    // Spec 1.H8: a new key goes into the tree and, in the sequence, right after its parent.
    static <K, V> void insert(HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i, HashMap.Cell<K, V> n) {
        hangAfter(table, roots, i, attach(roots[i], n), n);
    }

    /** Spec 1.H8: n, just attached under p, follows p in the sequence; the tree is then rebalanced. */
    private static <K, V> void hangAfter(HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots, int i, HashMap.Cell<K, V> p,
            HashMap.Cell<K, V> n) {
        n.next = p.next;
        p.next = n;
        HashMap.Cell<K, V> root = (HashMap.Cell<K, V>) RedBlack.fixInsert(roots[i], n);
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
     * Spec 1.H2, H5, H12: moves each bin of the old table into the new one, emptying the old bin as
     * it goes (so a walk over the old table ends early, spec 9.F3).
     */
    static <K, V> void spread(HashMap<K, V> owner, HashMap.Cell<K, V>[] old, HashMap.Cell<K, V>[] oldRoots,
            HashMap.Cell<K, V>[] table, HashMap.Cell<K, V>[] roots) {
        int oldN = old == null ? 0 : old.length;
        for (int j = 0; j < oldN; j++) {
            HashMap.Cell<K, V> bin = old[j];
            if (bin == null) {
                continue;
            }
            old[j] = null;
            if (bin.next == null) {
                // Spec 1.H12: a bin of one entry moves as it is, a tree staying a tree.
                int to = bin.hash & (table.length - 1);
                table[to] = bin;
                if (oldRoots != null && oldRoots[j] != null) {
                    roots[to] = bin;
                }
            } else if (oldRoots != null && oldRoots[j] != null) {
                divide(owner, bin, oldRoots[j], j, oldN, table, roots);
            } else {
                HashMap.Cell<K, V>[] two = halves(bin, oldN);
                table[j] = two[0];
                table[j + oldN] = two[1];
            }
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
