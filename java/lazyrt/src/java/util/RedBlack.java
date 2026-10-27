package java.util;

/**
 * Red-black tree insertion and deletion with rebalancing as in Cormen, Leiserson, Rivest and
 * Stein, "Introduction to Algorithms" (spec 1.H7, 6.M1). Absent children count as black. Every
 * method takes the current root and returns the root after the change.
 */
final class RedBlack {
    private RedBlack() {
    }

    private static boolean isRed(MapNode<?, ?> n) {
        return n != null && n.red;
    }

    /** Hangs `n` under `parent` on the given side; then call fixInsert. */
    static <K, V> void attach(MapNode<K, V> parent, MapNode<K, V> n, boolean asLeft) {
        n.up = parent;
        if (asLeft) {
            parent.left = n;
        } else {
            parent.right = n;
        }
    }

    /** Restores the red-black rules after `z` was attached as a leaf (RB-INSERT-FIXUP). */
    static <K, V> MapNode<K, V> fixInsert(MapNode<K, V> root, MapNode<K, V> z) {
        z.red = true;
        while (z != root && z.up.red) {
            MapNode<K, V> p = z.up;
            MapNode<K, V> g = p.up;
            boolean parentLeft = p == g.left;
            MapNode<K, V> uncle = parentLeft ? g.right : g.left;
            if (isRed(uncle)) {
                p.red = false;
                uncle.red = false;
                g.red = true;
                z = g;
                continue;
            }
            if (z == (parentLeft ? p.right : p.left)) {
                z = p;
                root = rotate(root, z, parentLeft);
                p = z.up;
            }
            p.red = false;
            g.red = true;
            root = rotate(root, g, !parentLeft);
        }
        root.red = false;
        return root;
    }

    /** Unlinks `z` (RB-DELETE): a node with two children is replaced by its in-order successor. */
    static <K, V> MapNode<K, V> remove(MapNode<K, V> root, MapNode<K, V> z) {
        MapNode<K, V> x;
        MapNode<K, V> xParent;
        boolean removedRed = z.red;
        if (z.left == null || z.right == null) {
            x = z.left != null ? z.left : z.right;
            xParent = z.up;
            root = transplant(root, z, x);
        } else {
            MapNode<K, V> y = first(z.right);
            removedRed = y.red;
            x = y.right;
            if (y.up == z) {
                xParent = y;
            } else {
                xParent = y.up;
                root = transplant(root, y, y.right);
                y.right = z.right;
                y.right.up = y;
            }
            root = transplant(root, z, y);
            y.left = z.left;
            y.left.up = y;
            y.red = z.red;
        }
        if (!removedRed) {
            root = fixRemove(root, x, xParent);
        }
        z.unlinkTree();
        return root;
    }

    /** Puts `v` (possibly null) where `u` hangs. */
    private static <K, V> MapNode<K, V> transplant(MapNode<K, V> root, MapNode<K, V> u, MapNode<K, V> v) {
        if (u.up == null) {
            root = v;
        } else if (u == u.up.left) {
            u.up.left = v;
        } else {
            u.up.right = v;
        }
        if (v != null) {
            v.up = u.up;
        }
        return root;
    }

    /** RB-DELETE-FIXUP; x may be null, so its parent is passed explicitly. */
    private static <K, V> MapNode<K, V> fixRemove(MapNode<K, V> root, MapNode<K, V> x, MapNode<K, V> xParent) {
        while (x != root && !isRed(x)) {
            boolean isLeft = x == xParent.left;
            MapNode<K, V> w = isLeft ? xParent.right : xParent.left;
            if (isRed(w)) {
                w.red = false;
                xParent.red = true;
                root = rotate(root, xParent, isLeft);
                w = isLeft ? xParent.right : xParent.left;
            }
            MapNode<K, V> near = isLeft ? w.left : w.right;
            MapNode<K, V> far = isLeft ? w.right : w.left;
            if (!isRed(near) && !isRed(far)) {
                w.red = true;
                x = xParent;
                xParent = x.up;
                continue;
            }
            if (!isRed(far)) {
                near.red = false;
                w.red = true;
                root = rotate(root, w, !isLeft);
                w = isLeft ? xParent.right : xParent.left;
                far = isLeft ? w.right : w.left;
            }
            w.red = xParent.red;
            xParent.red = false;
            far.red = false;
            root = rotate(root, xParent, isLeft);
            x = root;
            break;
        }
        if (x != null) {
            x.red = false;
        }
        return root;
    }

    /** A left rotation at x when `toLeft`, else a right rotation. */
    private static <K, V> MapNode<K, V> rotate(MapNode<K, V> root, MapNode<K, V> x, boolean toLeft) {
        MapNode<K, V> y = toLeft ? x.right : x.left;
        MapNode<K, V> inner = toLeft ? y.left : y.right;
        if (toLeft) {
            x.right = inner;
        } else {
            x.left = inner;
        }
        if (inner != null) {
            inner.up = x;
        }
        root = transplant(root, x, y);
        if (toLeft) {
            y.left = x;
        } else {
            y.right = x;
        }
        x.up = y;
        return root;
    }

    static <K, V> MapNode<K, V> first(MapNode<K, V> n) {
        if (n != null) {
            while (n.left != null) {
                n = n.left;
            }
        }
        return n;
    }

    static <K, V> MapNode<K, V> last(MapNode<K, V> n) {
        if (n != null) {
            while (n.right != null) {
                n = n.right;
            }
        }
        return n;
    }

    static <K, V> MapNode<K, V> following(MapNode<K, V> n) {
        if (n.right != null) {
            return first(n.right);
        }
        MapNode<K, V> p = n.up;
        while (p != null && n == p.right) {
            n = p;
            p = p.up;
        }
        return p;
    }

    static <K, V> MapNode<K, V> preceding(MapNode<K, V> n) {
        if (n.left != null) {
            return last(n.left);
        }
        MapNode<K, V> p = n.up;
        while (p != null && n == p.left) {
            n = p;
            p = p.up;
        }
        return p;
    }
}
