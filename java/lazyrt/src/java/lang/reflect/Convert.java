package java.lang.reflect;

/** Unboxing and widening of boxed values to a target type, shared by Array, Field, Method and Constructor. */
final class Convert {
    /** Returned when a value does not fit the target type. */
    static final Object FAIL = new Object();

    private Convert() {
    }

    /** The primitive kind letter of a wrapper object, or 0. */
    static char kindOf(Object v) {
        if (v instanceof Integer) return 'I';
        if (v instanceof Long) return 'J';
        if (v instanceof Boolean) return 'Z';
        if (v instanceof Byte) return 'B';
        if (v instanceof Short) return 'S';
        if (v instanceof Character) return 'C';
        if (v instanceof Float) return 'F';
        if (v instanceof Double) return 'D';
        return 0;
    }

    /** The kind letter of a primitive class, or 0 for other classes. */
    static char kindOfClass(Class<?> c) {
        if (c == int.class) return 'I';
        if (c == long.class) return 'J';
        if (c == boolean.class) return 'Z';
        if (c == byte.class) return 'B';
        if (c == short.class) return 'S';
        if (c == char.class) return 'C';
        if (c == float.class) return 'F';
        if (c == double.class) return 'D';
        return 0;
    }

    private static boolean widens(char from, char to) {
        if (from == to) return true;
        switch (from) {
            case 'B': return "SIJFD".indexOf(to) >= 0;
            case 'S':
            case 'C': return "IJFD".indexOf(to) >= 0;
            case 'I': return "JFD".indexOf(to) >= 0;
            case 'J': return "FD".indexOf(to) >= 0;
            case 'F': return to == 'D';
            default: return false;
        }
    }

    private static long integral(Object v) {
        return v instanceof Character ? ((Character) v).charValue() : ((Number) v).longValue();
    }

    /** `v` as an object of exactly the primitive kind `to`, or FAIL if it does not widen to it. */
    static Object primitive(Object v, char to) {
        char from = kindOf(v);
        if (from == 0 || to == 0 || !widens(from, to)) {
            return FAIL;
        }
        if (from == to) {
            return v;
        }
        switch (to) {
            case 'S': return Short.valueOf((short) integral(v));
            case 'I': return Integer.valueOf((int) integral(v));
            case 'J': return Long.valueOf(integral(v));
            case 'F': return Float.valueOf((float) integral(v));
            default: return Double.valueOf(from == 'F' ? ((Float) v).doubleValue() : (from == 'J'
                    ? (double) integral(v) : (double) (int) integral(v)));
        }
    }

    /** `v` converted for a parameter or field of type `t`: widened for primitives, checked for others. */
    static Object to(Object v, Class<?> t) {
        if (t.isPrimitive()) {
            return primitive(v, kindOfClass(t));
        }
        return v == null || t.isInstance(v) ? v : FAIL;
    }
}
