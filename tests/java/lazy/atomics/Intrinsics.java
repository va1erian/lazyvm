package lazy.atomics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import lazy.internal.Atomics;

/** lazy.internal.Atomics directly: static fields, and what the natives refuse. */
public class Intrinsics {
    interface Op {
        Object run() throws Exception;
    }

    static void attempt(String name, Op op) {
        try {
            System.out.println(name + ": " + op.run());
        } catch (Throwable e) {
            System.out.println(name + ": " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    static class Statics {
        static volatile int i = 5;
        static volatile long l = 6;
        static volatile String s = "start";
        static volatile CharSequence seq;
        static final String FIXED = String.valueOf(1);

        static {
            System.out.println("Statics initialized");
        }
    }

    static class Failing {
        static volatile int i = Integer.parseInt("not a number");
    }

    static class Instance {
        volatile int i;
        volatile long l;
        volatile String s;
        volatile CharSequence seq;
        volatile int[] ints;
        volatile byte b;
        volatile short sh;
        volatile char c;
        volatile boolean z;
        volatile float f;
        volatile double d;
        final int fixed = 1;
    }

    static class SubInstance extends Instance {
    }

    static Field f(Class<?> c, String name) throws Exception {
        return c.getDeclaredField(name);
    }

    static void statics() throws Exception {
        Field i = f(Statics.class, "i");
        Field l = f(Statics.class, "l");
        Field s = f(Statics.class, "s");
        System.out.println("fields found");
        attempt("static add", () -> Atomics.getAndAddInt(null, i, 2));
        attempt("static value", () -> Statics.i);
        attempt("static cas no", () -> Atomics.compareAndSetInt(null, i, 0, 1));
        attempt("static cas yes", () -> Atomics.compareAndSetInt(new Object(), i, 7, 1));
        attempt("static swap", () -> Atomics.getAndSetInt("ignored", i, Integer.MAX_VALUE));
        attempt("static wrap", () -> Atomics.getAndAddInt(null, i, 1) + " " + Statics.i);
        attempt("static long add", () -> Atomics.getAndAddLong(null, l, 1L << 40) + " " + Statics.l);
        attempt("static long cas", () -> Atomics.compareAndSetLong(null, l, 6L + (1L << 40), -1) + " " + Statics.l);
        attempt("static long swap", () -> Atomics.getAndSetLong(null, l, 3) + " " + Statics.l);
        attempt("static ref read", () -> Atomics.getRef(null, s));
        attempt("static ref cas", () -> Atomics.compareAndSetRef(null, s, Statics.s, "next") + " " + Statics.s);
        attempt("static ref swap", () -> Atomics.getAndSetRef(null, s, null) + " " + Statics.s);
        attempt("static ref wrong value", () -> Atomics.getAndSetRef(null, s, Integer.valueOf(1)));
        attempt("static final", () -> Atomics.getAndSetRef(null, f(Statics.class, "FIXED"), "x"));
        attempt("static failing init", () -> Atomics.getAndAddInt(null, f(Failing.class, "i"), 1));
        attempt("static failing again", () -> Atomics.getAndAddInt(null, f(Failing.class, "i"), 1));
    }

    static void receivers() throws Exception {
        Field i = f(Instance.class, "i");
        attempt("null target", () -> Atomics.getAndAddInt(null, i, 1));
        attempt("wrong target", () -> Atomics.getAndAddInt("text", i, 1));
        attempt("array target", () -> Atomics.getAndAddInt(new int[1], i, 1));
        attempt("sub target", () -> Atomics.getAndAddInt(new SubInstance(), i, 1));
        attempt("null field", () -> Atomics.getAndAddInt(new Instance(), (Field) null, 1));
        attempt("null field long", () -> Atomics.getAndSetLong(new Instance(), (Field) null, 1));
        attempt("null field ref", () -> Atomics.getRef(new Instance(), null));
    }

    static void kinds() throws Exception {
        Instance t = new Instance();
        for (String name : new String[] {"l", "s", "b", "sh", "c", "z", "f", "d", "ints"}) {
            attempt("int op on " + name, () -> Atomics.getAndAddInt(t, f(Instance.class, name), 1));
        }
        for (String name : new String[] {"i", "s", "b", "f", "d", "ints"}) {
            attempt("long op on " + name, () -> Atomics.getAndAddLong(t, f(Instance.class, name), 1));
        }
        for (String name : new String[] {"i", "l", "b", "f", "d"}) {
            attempt("ref op on " + name, () -> Atomics.getRef(t, f(Instance.class, name)));
        }
        attempt("final field", () -> Atomics.getAndAddInt(t, f(Instance.class, "fixed"), 1));
    }

    static void referenceValues() throws Exception {
        Instance t = new Instance();
        Field s = f(Instance.class, "s");
        Field seq = f(Instance.class, "seq");
        Field ints = f(Instance.class, "ints");
        attempt("wrong value", () -> Atomics.getAndSetRef(t, s, Integer.valueOf(1)));
        attempt("wrong update, no match", () -> Atomics.compareAndSetRef(t, s, "other", Integer.valueOf(1)));
        attempt("wrong expect is fine", () -> Atomics.compareAndSetRef(t, s, Integer.valueOf(1), "x"));
        attempt("still null", () -> t.s);
        attempt("interface field", () -> Atomics.getAndSetRef(t, seq, "text") + " " + t.seq);
        attempt("interface field wrong", () -> Atomics.getAndSetRef(t, seq, new Object()));
        attempt("array field wrong", () -> Atomics.getAndSetRef(t, ints, new long[1]));
        int[] array = new int[1];
        attempt("array field right", () -> Atomics.compareAndSetRef(t, ints, null, array) + " " + (t.ints == array));
        attempt("null value", () -> Atomics.getAndSetRef(t, ints, null) == array);
    }

    static void arrays() {
        int[] ints = {1, 2};
        long[] longs = {3, 4};
        String[] strings = {"a", "b"};
        attempt("int add", () -> Atomics.getAndAddInt(ints, 1, 5) + " " + ints[1]);
        attempt("int cas", () -> Atomics.compareAndSetInt(ints, 0, 9, 0) + " " + Atomics.compareAndSetInt(ints, 0, 1, 0));
        attempt("int swap", () -> Atomics.getAndSetInt(ints, 0, 8) + " " + ints[0]);
        attempt("long add", () -> Atomics.getAndAddLong(longs, 1, 1L << 40) + " " + longs[1]);
        attempt("long cas", () -> Atomics.compareAndSetLong(longs, 0, 9, 0) + " " + Atomics.compareAndSetLong(longs, 0, 3, 0));
        attempt("long swap", () -> Atomics.getAndSetLong(longs, 0, 8) + " " + longs[0]);
        attempt("ref swap", () -> Atomics.getAndSetRef(strings, 0, "c") + " " + strings[0]);
        attempt("ref cas", () -> Atomics.compareAndSetRef(strings, 0, "zz", "d") + " "
                + Atomics.compareAndSetRef(strings, 0, strings[0], "d") + " " + strings[0]);
        attempt("int low", () -> Atomics.getAndAddInt(ints, -1, 1));
        attempt("int high", () -> Atomics.getAndAddInt(ints, 2, 1));
        attempt("long low", () -> Atomics.getAndSetLong(longs, -1, 1));
        attempt("long high", () -> Atomics.compareAndSetLong(longs, 2, 1, 1));
        attempt("ref low", () -> Atomics.getAndSetRef(strings, -1, "x"));
        attempt("ref high", () -> Atomics.compareAndSetRef(strings, 2, "x", "x"));
        attempt("int null", () -> Atomics.getAndAddInt((int[]) null, 0, 1));
        attempt("long null", () -> Atomics.getAndAddLong((long[]) null, 0, 1));
        attempt("ref null", () -> Atomics.getAndSetRef((Object[]) null, 0, "x"));
        attempt("store wrong", () -> Atomics.getAndSetRef((Object[]) strings, 0, Integer.valueOf(1)));
        attempt("store wrong cas", () -> Atomics.compareAndSetRef((Object[]) strings, 0, "zz", Integer.valueOf(1)));
        attempt("store null", () -> Atomics.getAndSetRef((Object[]) strings, 1, null) + " " + strings[1]);
        attempt("bounds before store", () -> Atomics.getAndSetRef((Object[]) strings, 9, Integer.valueOf(1)));
        Object[][] nested = {{"n"}};
        attempt("nested array", () -> Atomics.getAndSetRef(nested, 0, new String[] {"m"}).getClass().getName());
    }

    /** A Field whose VM-visible parts were changed by reflection must still be safe. */
    static void forged() throws Exception {
        Field slot = Field.class.getDeclaredField("slot");
        Field clazz = Field.class.getDeclaredField("clazz");
        slot.setAccessible(true);
        clazz.setAccessible(true);
        Instance t = new Instance();
        Field out = f(Instance.class, "i");
        slot.setInt(out, 100000);
        attempt("slot past the end", () -> Atomics.getAndAddInt(t, out, 1));
        Field negative = f(Instance.class, "i");
        slot.setInt(negative, -1);
        attempt("slot negative", () -> Atomics.getAndAddInt(t, negative, 1));
        Field other = f(Instance.class, "i");
        slot.setInt(other, slot.getInt(f(Instance.class, "s")));
        attempt("slot of another kind", () -> Atomics.getAndAddInt(t, other, 1));
        Field moved = f(Instance.class, "i");
        clazz.set(moved, String.class);
        attempt("class changed", () -> Atomics.getAndAddInt(t, moved, 1));
        Field primitive = f(Instance.class, "i");
        clazz.set(primitive, int.class);
        attempt("class has no fields", () -> Atomics.getAndAddInt(t, primitive, 1));
        Field arrayClass = f(Instance.class, "i");
        clazz.set(arrayClass, int[].class);
        attempt("class is an array", () -> Atomics.getAndAddInt(new int[1], arrayClass, 1));
        Field none = f(Instance.class, "i");
        clazz.set(none, null);
        attempt("no class", () -> Atomics.getAndAddInt(t, none, 1));
        Field dropped = f(Instance.class, "s");
        slot.setInt(dropped, 100000);
        attempt("ref slot past the end", () -> Atomics.compareAndSetRef(t, dropped, null, "x"));
    }

    static Class<?> whoCalled() {
        return Atomics.callerClass();
    }

    static class Other {
        static Class<?> ask() {
            return whoCalled();
        }
    }

    static void callers() throws Exception {
        System.out.println(whoCalled().getName());
        System.out.println(Other.ask().getName());
        Method m = Intrinsics.class.getDeclaredMethod("whoCalled");
        System.out.println(((Class<?>) m.invoke(null)).getName());
    }

    static volatile int hammered;

    static void threads() throws Exception {
        Field field = f(Intrinsics.class, "hammered");
        int[] cell = new int[1];
        Thread[] all = new Thread[4];
        for (int t = 0; t < all.length; t++) {
            all[t] = new Thread(() -> {
                for (int i = 0; i < 20000; i++) {
                    Atomics.getAndAddInt(null, field, 1);
                    Atomics.getAndAddInt(cell, 0, 2);
                }
            });
            all[t].start();
        }
        for (Thread t : all) {
            t.join();
        }
        System.out.println(hammered + " " + cell[0]);
    }

    public static void main(String[] args) throws Exception {
        statics();
        receivers();
        kinds();
        referenceValues();
        arrays();
        forged();
        callers();
        threads();
    }
}
