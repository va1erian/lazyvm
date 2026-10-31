package lazy.internal;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** How the tree bins of {@code HashMap} and {@code ConcurrentHashMap} order two keys of one hash. */
public final class KeyOrder {
    /** The last class asked about in qualifies(), with the answer. */
    private static volatile Object[] lastAnswer = {null, Boolean.FALSE};

    private KeyOrder() {
    }

    /** k.compareTo(pk) for two keys of one qualifying class, else 0: nothing is known. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compared(Object k, Object pk) {
        if (k != null && pk != null && k.getClass() == pk.getClass() && qualifies(k.getClass())) {
            return ((Comparable) k).compareTo(pk);
        }
        return 0;
    }

    /** A side for two keys {@link #compared} cannot tell apart: by class name, or else by identity. */
    public static int anySide(Object k, Object pk) {
        if (k != null && pk != null) {
            int r = k.getClass().getName().compareTo(pk.getClass().getName());
            if (r != 0) {
                return r;
            }
        }
        return System.identityHashCode(k) <= System.identityHashCode(pk) ? -1 : 1;
    }

    // String, or a class that itself declares Comparable<itself>: on anything else compareTo could
    // throw a ClassCastException the caller of a map operation has no reason to expect.
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
}
