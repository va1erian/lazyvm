package lang;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Type checks in code LazyVM's JIT compiled: checkcast, instanceof and
 * aastore against classes, interfaces and arrays, with null. Each method runs
 * often enough to be compiled (and inlined where it is small), then once with
 * a value that fails, which must throw as in the interpreter.
 */
public class JitTypes {
    interface Shape { int area(); }
    static class Sq implements Shape { public int area() { return 4; } }
    static class Big extends Sq { public int area() { return 9; } }

    static int cast(Object o) {
        return ((Shape) o).area();
    }

    static int kinds(Object o) {
        int k = 0;
        if (o instanceof Shape) k |= 1;
        if (o instanceof Sq) k |= 2;
        if (o instanceof Big) k |= 4;
        if (o instanceof Object[]) k |= 8;
        if (o instanceof Serializable) k |= 16;
        if (o instanceof Cloneable) k |= 32;
        if (o instanceof Number[]) k |= 64;
        return k;
    }

    static void put(Object[] a, int i, Object v) {
        a[i] = v;
    }

    static String first(List<String> list) {
        return list.get(0);
    }

    static String where(Throwable t) {
        return t.getClass().getSimpleName() + " " + t.getStackTrace()[0].getMethodName();
    }

    public static void main(String[] args) {
        Object[] values = { new Sq(), new Big(), null, new Integer[1], "s", new int[1], new Object[0] };
        long sum = 0;
        for (int i = 0; i < 300; i++) {
            sum += cast(i % 2 == 0 ? new Sq() : new Big());
            sum += kinds(values[i % values.length]);
        }
        System.out.println("cast and kinds " + sum);
        for (Object v : values) {
            System.out.print(kinds(v) + " ");
        }
        System.out.println();
        try {
            cast("not a shape");
        } catch (ClassCastException e) {
            System.out.println(where(e));
        }
        System.out.println("null cast " + (cast2(null) == null));

        Object[] objects = new Object[4];
        Number[] numbers = new Integer[4];
        for (int i = 0; i < 300; i++) {
            put(objects, i & 3, i % 3 == 0 ? null : "x" + i);
            put(numbers, i & 3, i % 3 == 0 ? null : Integer.valueOf(i));
        }
        System.out.println("stored " + objects[1] + " " + numbers[2] + " " + numbers[3]);
        try {
            put(numbers, 0, Double.valueOf(1));
        } catch (ArrayStoreException e) {
            System.out.println(where(e) + " " + numbers[0]);
        }
        try {
            put(objects, 4, "out");
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println(where(e));
        }
        try {
            put(null, 0, "none");
        } catch (NullPointerException e) {
            System.out.println(where(e));
        }

        List<String> names = new ArrayList<>();
        names.add("a");
        int len = 0;
        for (int i = 0; i < 300; i++) {
            len += first(names).length();
        }
        System.out.println("generic " + len);
        @SuppressWarnings("unchecked")
        List<String> raw = (List<String>) (List<?>) names;
        ((List<Object>) (List<?>) raw).set(0, Integer.valueOf(7));
        try {
            System.out.println(first(raw).length());
        } catch (ClassCastException e) {
            System.out.println(where(e));
        }
    }

    static Shape cast2(Object o) {
        return (Shape) o;
    }
}
