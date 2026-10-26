package lazy.internal;

import java.lang.reflect.Array;

/** Copy, equality, hash and text of annotation member values, which may be arrays of any kind. */
final class AnnotationValues {
    private AnnotationValues() {
    }

    static Object copy(Object v) {
        if (v == null || !v.getClass().isArray()) {
            return v;
        }
        int n = Array.getLength(v);
        Object out = Array.newInstance(v.getClass().getComponentType(), n);
        System.arraycopy(v, 0, out, 0, n);
        return out;
    }

    static boolean equal(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (!a.getClass().isArray()) {
            return a.equals(b);
        }
        if (a.getClass() != b.getClass()) {
            return false;
        }
        int n = Array.getLength(a);
        if (n != Array.getLength(b)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            Object x = Array.get(a, i);
            Object y = Array.get(b, i);
            if (x == null ? y != null : !x.equals(y)) {
                return false;
            }
        }
        return true;
    }

    static int hash(Object v) {
        if (!v.getClass().isArray()) {
            return v.hashCode();
        }
        int h = 1;
        int n = Array.getLength(v);
        for (int i = 0; i < n; i++) {
            Object e = Array.get(v, i);
            h = 31 * h + (e == null ? 0 : e.hashCode());
        }
        return h;
    }

    static String text(Object v) {
        if (v == null || !v.getClass().isArray()) {
            return String.valueOf(v);
        }
        StringBuilder sb = new StringBuilder("[");
        int n = Array.getLength(v);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(Array.get(v, i)));
        }
        return sb.append(']').toString();
    }
}
