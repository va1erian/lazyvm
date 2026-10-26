package java.lang.reflect;

public final class Array {
    private Array() {
    }

    private static native Object newArray(Class<?> componentType, int length);

    public static native int getLength(Object array);

    private static native Object get0(Object array, int index);

    private static native void set0(Object array, int index, Object value);

    public static Object newInstance(Class<?> componentType, int length) {
        if (componentType == null) {
            throw new NullPointerException();
        }
        if (componentType == void.class) {
            throw new IllegalArgumentException();
        }
        if (length < 0) {
            throw new NegativeArraySizeException();
        }
        return newArray(componentType, length);
    }

    public static Object newInstance(Class<?> componentType, int... dimensions) {
        if (componentType == null) {
            throw new NullPointerException();
        }
        if (dimensions.length == 0 || componentType == void.class) {
            throw new IllegalArgumentException();
        }
        for (int i = 0; i < dimensions.length; i++) {
            if (dimensions[i] < 0) {
                throw new NegativeArraySizeException();
            }
        }
        return build(componentType, dimensions, 0);
    }

    /** Builds the nested arrays for dimensions[level..]; the element class of each level is an array class. */
    private static Object build(Class<?> leaf, int[] dims, int level) {
        int rest = dims.length - level - 1;
        Class<?> element = leaf;
        for (int i = 0; i < rest; i++) {
            element = newArray(element, 0).getClass();
        }
        Object array = newArray(element, dims[level]);
        if (rest > 0) {
            for (int i = 0; i < dims[level]; i++) {
                set0(array, i, build(leaf, dims, level + 1));
            }
        }
        return array;
    }

    private static void check(Object array, int index) {
        if (array == null) {
            throw new NullPointerException();
        }
        int length = getLength(array);
        if (index < 0 || index >= length) {
            throw new ArrayIndexOutOfBoundsException();
        }
    }

    public static Object get(Object array, int index) {
        check(array, index);
        return get0(array, index);
    }

    /** The element read through a primitive getter: the array must be primitive and widen to `to`. */
    private static Object read(Object array, int index, char to) {
        check(array, index);
        if (!array.getClass().getComponentType().isPrimitive()) {
            throw new IllegalArgumentException("Argument is not an array");
        }
        Object v = Convert.primitive(get0(array, index), to);
        if (v == Convert.FAIL) {
            throw new IllegalArgumentException("argument type mismatch");
        }
        return v;
    }

    public static boolean getBoolean(Object array, int index) {
        return ((Boolean) read(array, index, 'Z')).booleanValue();
    }

    public static byte getByte(Object array, int index) {
        return ((Byte) read(array, index, 'B')).byteValue();
    }

    public static char getChar(Object array, int index) {
        return ((Character) read(array, index, 'C')).charValue();
    }

    public static short getShort(Object array, int index) {
        return ((Short) read(array, index, 'S')).shortValue();
    }

    public static int getInt(Object array, int index) {
        return ((Integer) read(array, index, 'I')).intValue();
    }

    public static long getLong(Object array, int index) {
        return ((Long) read(array, index, 'J')).longValue();
    }

    public static float getFloat(Object array, int index) {
        return ((Float) read(array, index, 'F')).floatValue();
    }

    public static double getDouble(Object array, int index) {
        return ((Double) read(array, index, 'D')).doubleValue();
    }

    public static void set(Object array, int index, Object value) {
        check(array, index);
        Class<?> element = array.getClass().getComponentType();
        if (element.isPrimitive()) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            Object v = Convert.to(value, element);
            if (v == Convert.FAIL) {
                throw new IllegalArgumentException("argument type mismatch");
            }
            set0(array, index, v);
        } else {
            if (value != null && !element.isInstance(value)) {
                throw new IllegalArgumentException("array element type mismatch");
            }
            set0(array, index, value);
        }
    }

    private static void write(Object array, int index, Object boxed) {
        check(array, index);
        Class<?> element = array.getClass().getComponentType();
        if (!element.isPrimitive()) {
            throw new IllegalArgumentException("Argument is not an array");
        }
        Object v = Convert.to(boxed, element);
        if (v == Convert.FAIL) {
            throw new IllegalArgumentException("argument type mismatch");
        }
        set0(array, index, v);
    }

    public static void setBoolean(Object array, int index, boolean z) {
        write(array, index, Boolean.valueOf(z));
    }

    public static void setByte(Object array, int index, byte b) {
        write(array, index, Byte.valueOf(b));
    }

    public static void setChar(Object array, int index, char c) {
        write(array, index, Character.valueOf(c));
    }

    public static void setShort(Object array, int index, short s) {
        write(array, index, Short.valueOf(s));
    }

    public static void setInt(Object array, int index, int i) {
        write(array, index, Integer.valueOf(i));
    }

    public static void setLong(Object array, int index, long l) {
        write(array, index, Long.valueOf(l));
    }

    public static void setFloat(Object array, int index, float f) {
        write(array, index, Float.valueOf(f));
    }

    public static void setDouble(Object array, int index, double d) {
        write(array, index, Double.valueOf(d));
    }
}
