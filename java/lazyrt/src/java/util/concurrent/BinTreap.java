package java.util.concurrent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import lazy.internal.KeyOrder;

/**
 * A long bin of a {@link HashCore}, as an immutable search tree whose root sits in the table's slot. A
 * write builds a new root that shares what did not change, with no lock, since it runs the keys'
 * {@code equals} and {@code compareTo}; the core then publishes that root if the old one is still in
 * the slot. A reader just walks the root it read.
 *
 * <p>Keys go by hash, then by {@link KeyOrder}; within that the shape follows a random rank, higher
 * above lower, so the order keys arrive in cannot make it lopsided. Keys of no known order search both
 * sides, which is only as slow as a list. A compare that disagrees with {@code equals} on keys it calls
 * different is the keys' fault (concurrent.md, "What is not pinned").
 */
final class BinTreap<K, V> {
    private static final int SAME = 2;
    private static int rankSeed = (int) System.nanoTime() | 1;

    private final HashCore.Node<K, V> entry;
    private final BinTreap<K, V> left;
    private final BinTreap<K, V> right;
    private final int rank;

    private BinTreap(HashCore.Node<K, V> entry, BinTreap<K, V> left, BinTreap<K, V> right, int rank) {
        this.entry = entry;
        this.left = left;
        this.right = right;
        this.rank = rank;
    }

    // Racing threads may draw the same number: only the shape depends on it.
    private static int nextRank() {
        int x = rankSeed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        rankSeed = x;
        return x;
    }

    private BinTreap<K, V> withLeft(BinTreap<K, V> l) {
        return new BinTreap<>(entry, l, right, rank);
    }

    private BinTreap<K, V> withRight(BinTreap<K, V> r) {
        return new BinTreap<>(entry, left, r, rank);
    }

    /**
     * What the keys' own code says about a key and an entry of its hash: {@link #SAME}, or which way
     * to go, 0 if it cannot tell. {@code told}, if any, remembers it for a later search of this write.
     */
    private static <K, V> int ask(HashCore.Node<K, V> e, Object key, IdentityHashMap<HashCore.Node<K, V>, Integer> told) {
        Integer known = told == null ? null : told.get(e);
        if (known != null) {
            return known;
        }
        int r = e.key == key || key.equals(e.key) ? SAME : Integer.signum(KeyOrder.compared(key, e.key));
        if (told != null) {
            told.put(e, r);
        }
        return r;
    }

    static <K, V> HashCore.Node<K, V> find(BinTreap<K, V> t, int hash, Object key, IdentityHashMap<HashCore.Node<K, V>, Integer> told) {
        while (t != null) {
            HashCore.Node<K, V> e = t.entry;
            if (hash != e.hash) {
                t = hash < e.hash ? t.left : t.right;
                continue;
            }
            int r = ask(e, key, told);
            if (r == SAME) {
                return e;
            }
            if (r == 0) {
                HashCore.Node<K, V> inRight = find(t.right, hash, key, told);
                if (inRight != null) {
                    return inRight;
                }
                t = t.left;
            } else {
                t = r < 0 ? t.left : t.right;
            }
        }
        return null;
    }

    /** Everything in {@code a} goes before everything in {@code b}. */
    private static <K, V> BinTreap<K, V> merge(BinTreap<K, V> a, BinTreap<K, V> b) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }
        return a.rank > b.rank ? a.withRight(merge(a.right, b)) : b.withLeft(merge(a, b.left));
    }

    /**
     * The entries whose {@code bit} of the hash is as {@code wanted}, as new entries, because the core
     * grows with its monitor held and new nodes (a walk of the old table sees a table that stays).
     * Order and ranks carry over, so this calls no user code.
     */
    static <K, V> BinTreap<K, V> half(BinTreap<K, V> t, int bit, int wanted) {
        if (t == null) {
            return null;
        }
        BinTreap<K, V> l = half(t.left, bit, wanted);
        BinTreap<K, V> r = half(t.right, bit, wanted);
        HashCore.Node<K, V> e = t.entry;
        return (e.hash & bit) != wanted ? merge(l, r) : new BinTreap<>(new HashCore.Node<>(e.hash, e.key, e.value), l, r, t.rank);
    }

    /**
     * A tree of new entries with the keys of a chain, which are put in {@code copies}. The compares are
     * user code, so the chain may change meanwhile, and the entries have no value yet.
     */
    static <K, V> BinTreap<K, V> ofChain(HashCore.Node<K, V> first, ArrayList<HashCore.Node<K, V>> copies) {
        BinTreap<K, V> root = null;
        for (HashCore.Node<K, V> n = first; n != null; n = n.next) {
            HashCore.Node<K, V> copy = new HashCore.Node<>(n.hash, n.key, null);
            copies.add(copy);
            root = new Descent<>(copy).add(root);
        }
        return root;
    }

    /** Gives the copies the chain's values, and says whether it still has just those keys, in that order. */
    static <K, V> boolean takeValues(HashCore.Node<K, V> first, ArrayList<HashCore.Node<K, V>> copies) {
        int i = 0;
        for (HashCore.Node<K, V> n = first; n != null; n = n.next) {
            if (i == copies.size() || copies.get(i).key != n.key) {
                return false;
            }
            copies.get(i++).value = n.value;
        }
        return i == copies.size();
    }

    @SuppressWarnings("unchecked")
    HashCore.Node<K, V>[] entries() {
        HashCore.Node<K, V>[] out = (HashCore.Node<K, V>[]) new HashCore.Node<?, ?>[count()];
        fill(out, 0);
        return out;
    }

    private int count() {
        return 1 + (left == null ? 0 : left.count()) + (right == null ? 0 : right.count());
    }

    private int fill(HashCore.Node<K, V>[] out, int at) {
        int i = left == null ? at : left.fill(out, at);
        out[i++] = entry;
        return right == null ? i : right.fill(out, i);
    }

    /** One write's search and rebuild, in a single pass: what it found, if anything, is in {@code found}. */
    static final class Descent<K, V> {
        private final int hash;
        private final Object key;
        /** False once {@code equals} cannot find anything further: both sides were searched, or it is a new key. */
        private boolean checking;
        private final IdentityHashMap<HashCore.Node<K, V>, Integer> told;
        final HashCore.Node<K, V> entry;
        HashCore.Node<K, V> found;

        /** For a key that may be in the tree. */
        Descent(int hash, Object key, HashCore.Node<K, V> entry, IdentityHashMap<HashCore.Node<K, V>, Integer> told) {
            this.hash = hash;
            this.key = key;
            this.entry = entry;
            this.told = told;
            this.checking = true;
        }

        /** For an entry known not to be in the tree. */
        Descent(HashCore.Node<K, V> entry) {
            this(entry.hash, entry.key, entry, null);
            this.checking = false;
        }

        /** {@code t} with the entry added, or {@code t} itself if the key was found. */
        BinTreap<K, V> add(BinTreap<K, V> t) {
            if (t == null) {
                return new BinTreap<>(entry, null, null, nextRank());
            }
            boolean toLeft;
            if (hash != t.entry.hash) {
                toLeft = hash < t.entry.hash;
            } else {
                Object other = t.entry.key;
                // Past the point where equals can find anything, only the order is wanted, if it costs.
                int r = checking || told != null ? ask(t.entry, key, told) : KeyOrder.compared(key, other);
                if (checking && r == SAME) {
                    found = t.entry;
                    return t;
                }
                if (r == 0) {
                    if (checking) {
                        checking = false;
                        found = find(t.left, hash, key, told);
                        found = found != null ? found : find(t.right, hash, key, told);
                        if (found != null) {
                            return t;
                        }
                    }
                    r = classOrder(key, other);
                }
                toLeft = r < 0;
            }
            return toLeft ? addedLeft(t) : addedRight(t);
        }

        /**
         * Keys of one class stay together, so that a key's search never meets keys of its own order
         * out of place, whatever else lies between and however the ranks turn the tree. Within a class
         * no side is better: the keys are searched both ways, and the ranks decide the shape.
         */
        private static int classOrder(Object a, Object b) {
            return a.getClass() == b.getClass() ? 1 : a.getClass().getName().compareTo(b.getClass().getName());
        }

        private BinTreap<K, V> addedLeft(BinTreap<K, V> t) {
            BinTreap<K, V> n = add(t.left);
            if (found != null) {
                return t;
            }
            return n.rank > t.rank ? n.withRight(t.withLeft(n.right)) : t.withLeft(n);
        }

        private BinTreap<K, V> addedRight(BinTreap<K, V> t) {
            BinTreap<K, V> n = add(t.right);
            if (found != null) {
                return t;
            }
            return n.rank > t.rank ? n.withLeft(t.withRight(n.left)) : t.withRight(n);
        }

        /** {@code t} without the key, or {@code t} itself if it is not there. */
        BinTreap<K, V> cut(BinTreap<K, V> t) {
            if (t == null) {
                return null;
            }
            HashCore.Node<K, V> e = t.entry;
            int r = hash < e.hash ? -1 : 1;
            if (hash == e.hash) {
                r = ask(e, key, told);
                if (r == SAME) {
                    found = e;
                    return merge(t.left, t.right);
                }
            }
            if (r >= 0) {
                BinTreap<K, V> c = cut(t.right);
                if (found != null) {
                    return t.withRight(c);
                }
            }
            if (r <= 0) {
                BinTreap<K, V> c = cut(t.left);
                if (found != null) {
                    return t.withLeft(c);
                }
            }
            return t;
        }
    }
}
