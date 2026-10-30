package lazy.atomics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import lazy.internal.Atomics;

/**
 * Source of tests/java/lazy/atomics/CraftedAtomics.class, which
 * `python tools/fixtures.py` writes with these casts to Field and to array
 * types removed, so each native receives an object of the wrong class.
 */
public class CraftedAtomics {
    volatile int i;

    static Object hide(Object o) {
        return o;
    }

    static void report(String name, Throwable e) {
        System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
    }

    public static void run() throws Exception {
        Field real = CraftedAtomics.class.getDeclaredField("i");
        Method method = CraftedAtomics.class.getMethod("run");
        Object[] notFields = {method, "text", new int[1], String.class};
        for (Object o : notFields) {
            String name = "as Field " + o.getClass().getName();
            try {
                System.out.println(Atomics.getAndAddInt(new CraftedAtomics(), (Field) hide(o), 1));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.getAndAddLong(new CraftedAtomics(), (Field) hide(o), 1L));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.compareAndSetRef(new CraftedAtomics(), (Field) hide(o), null, null));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.getRef(new CraftedAtomics(), (Field) hide(o)));
            } catch (Throwable e) {
                report(name, e);
            }
        }
        Object[] forInts = {new long[1], new Object[1], new byte[1], "text"};
        for (Object o : forInts) {
            String name = "as int[] " + o.getClass().getName();
            try {
                System.out.println(Atomics.getAndAddInt((int[]) hide(o), 0, 1));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.compareAndSetInt((int[]) hide(o), 0, 0, 1));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.getAndSetInt((int[]) hide(o), 0, 1));
            } catch (Throwable e) {
                report(name, e);
            }
        }
        Object[] forLongs = {new int[1], new Object[1], new double[1], "text"};
        for (Object o : forLongs) {
            String name = "as long[] " + o.getClass().getName();
            try {
                System.out.println(Atomics.getAndAddLong((long[]) hide(o), 0, 1L));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.compareAndSetLong((long[]) hide(o), 0, 0L, 1L));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.getAndSetLong((long[]) hide(o), 0, 1L));
            } catch (Throwable e) {
                report(name, e);
            }
        }
        Object[] forRefs = {new int[1], new long[1], "text"};
        for (Object o : forRefs) {
            String name = "as Object[] " + o.getClass().getName();
            try {
                System.out.println(Atomics.getAndSetRef((Object[]) hide(o), 0, null));
            } catch (Throwable e) {
                report(name, e);
            }
            try {
                System.out.println(Atomics.compareAndSetRef((Object[]) hide(o), 0, null, null));
            } catch (Throwable e) {
                report(name, e);
            }
        }
        System.out.println("still alive " + Atomics.getAndAddInt(new CraftedAtomics(), real, 1));
    }
}
