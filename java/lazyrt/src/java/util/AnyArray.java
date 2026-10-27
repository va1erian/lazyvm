package java.util;

import java.lang.reflect.Array;

/**
 * Arrays operations written once for every array type, reading elements through reflection.
 * Boxed elements give exactly the per-type rules of the Javadoc: String.valueOf prints them,
 * their hashCode is the element hash, and their equals treats NaN and -0.0 as Arrays.equals does.
 */
final class AnyArray {
    private AnyArray() {
    }

    static String text(Object a) {
        if (a == null) {
            return "null";
        }
        int n = Array.getLength(a);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(Array.get(a, i)));
        }
        return sb.append(']').toString();
    }

    static int hash(Object a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (int i = 0, n = Array.getLength(a); i < n; i++) {
            h = 31 * h + Support.hash(Array.get(a, i));
        }
        return h;
    }

    static boolean same(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || Array.getLength(a) != Array.getLength(b)) {
            return false;
        }
        for (int i = 0, n = Array.getLength(a); i < n; i++) {
            if (!Support.eq(Array.get(a, i), Array.get(b, i))) {
                return false;
            }
        }
        return true;
    }

    /** A new array of a's type and `length`, filled from a[from, ...) as far as a reaches. */
    static Object copy(Object a, int from, int length) {
        Object out = Array.newInstance(a.getClass().getComponentType(), length);
        System.arraycopy(a, from, out, 0, Math.min(Array.getLength(a) - from, length));
        return out;
    }

    /** copyOfRange's checks, then the copy. */
    static Object range(Object a, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        return copy(a, from, to - from);
    }

    /** Arrays.deepToString: an array already on the path from the root prints as [...]. */
    static void deepText(Object[] a, StringBuilder sb, List<Object[]> path) {
        path.add(a);
        sb.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object e = a[i];
            if (e instanceof Object[]) {
                if (onPath((Object[]) e, path)) {
                    sb.append("[...]");
                } else {
                    deepText((Object[]) e, sb, path);
                }
            } else if (e != null && e.getClass().isArray()) {
                sb.append(text(e));
            } else {
                sb.append(String.valueOf(e));
            }
        }
        sb.append(']');
        path.remove(path.size() - 1);
    }

    private static boolean onPath(Object[] a, List<Object[]> path) {
        for (Object[] p : path) {
            if (p == a) {
                return true;
            }
        }
        return false;
    }

    static int deepHash(Object[] a) {
        if (a == null) {
            return 0;
        }
        int h = 1;
        for (Object e : a) {
            int eh;
            if (e instanceof Object[]) {
                eh = deepHash((Object[]) e);
            } else if (e != null && e.getClass().isArray()) {
                eh = hash(e);
            } else {
                eh = Support.hash(e);
            }
            h = 31 * h + eh;
        }
        return h;
    }

    static boolean deepSame(Object[] a, Object[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!deepElementSame(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean deepElementSame(Object x, Object y) {
        if (x == y) {
            return true;
        }
        if (x == null || y == null) {
            return false;
        }
        if (x instanceof Object[] && y instanceof Object[]) {
            return deepSame((Object[]) x, (Object[]) y);
        }
        if (x.getClass().isArray() && x.getClass() == y.getClass()) {
            return same(x, y);
        }
        return x.equals(y);
    }
}
