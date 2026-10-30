package java.util.concurrent;

import lazy.internal.KeyOrder;

/**
 * A long bin of a {@link HashCore}, as an immutable search tree. A write builds a new root that shares
 * what did not change, with no lock, since it runs the keys' {@code equals} and {@code compareTo}; the
 * core then publishes that root if the old one is still there. A reader just walks the root it read.
 *
 * <p>Keys go by hash, then by {@link KeyOrder}; within that the shape follows a random rank, higher
 * above lower, so the order keys arrive in cannot make it lopsided. Keys of no known order search both
 * sides, which is only as slow as a list. A compare that disagrees with {@code equals} on keys it calls
 * different is the keys' fault (concurrent.md, "What is not pinned").
 */
final class BinTreap<K, V> {
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

    static <K, V> HashCore.Node<K, V> find(BinTreap<K, V> t, int hash, Object key) {
        while (t != null) {
            HashCore.Node<K, V> e = t.entry;
            if (hash != e.hash) {
                t = hash < e.hash ? t.left : t.right;
                continue;
            }
            if (e.key == key || key.equals(e.key)) {
                return e;
            }
            int r = KeyOrder.compared(key, e.key);
            if (r == 0) {
                HashCore.Node<K, V> inRight = find(t.right, hash, key);
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
        final HashCore.Node<K, V> entry;
        HashCore.Node<K, V> found;

        /** For a key that may be in the tree. */
        Descent(int hash, Object key, HashCore.Node<K, V> entry) {
            this.hash = hash;
            this.key = key;
            this.entry = entry;
            this.checking = true;
        }

        /** For an entry known not to be in the tree. */
        Descent(HashCore.Node<K, V> entry) {
            this(entry.hash, entry.key, entry);
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
                if (checking && (other == key || key.equals(other))) {
                    found = t.entry;
                    return t;
                }
                int r = KeyOrder.compared(key, other);
                if (r == 0) {
                    if (checking) {
                        checking = false;
                        found = find(t.left, hash, key);
                        found = found != null ? found : find(t.right, hash, key);
                        if (found != null) {
                            return t;
                        }
                    }
                    r = KeyOrder.tieBreak(key, other);
                }
                toLeft = r < 0;
            }
            return toLeft ? addedLeft(t) : addedRight(t);
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
                if (e.key == key || key.equals(e.key)) {
                    found = e;
                    return merge(t.left, t.right);
                }
                r = KeyOrder.compared(key, e.key);
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
