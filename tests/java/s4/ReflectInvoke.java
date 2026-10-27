package s4;

import java.lang.reflect.*;

/** Method.invoke, Constructor.newInstance and Field access: conversions, errors and wrapping. */
public class ReflectInvoke {
    public static class Calc {
        public int add(int a, int b) { return a + b; }
        public static long mul(long a, long b) { return a * b; }
        public double half(double d) { return d / 2; }
        public String join(String a, Object b) { return a + b; }
        private int secret(int x) { return x * 10; }
        public void boom() throws Exception { throw new java.io.IOException("io"); }
        public void rte() { throw new IllegalStateException("bad"); }
        public void err() { throw new AssertionError("err"); }
        public int sum(int... xs) { int t = 0; for (int x : xs) t += x; return t; }
        public void nothing() { }
        public char ch(char c) { return c; }
        public byte b(byte x) { return x; }
        public boolean z(boolean x) { return !x; }
    }

    interface Speaker { String speak(); default String twice() { return speak() + speak(); } }
    static class Animal implements Speaker { public String speak() { return "..."; } }
    static class Dog extends Animal {
        public String speak() { return "woof"; }
        public String toString() { return "Dog!"; }
    }

    public static abstract class Abs { public Abs() { } }
    public static class Pt {
        final int x, y;
        public Pt(int x, int y) { this.x = x; this.y = y; }
        private Pt(String s) { this(s.length(), 0); }
        public Pt() { throw new IllegalStateException("no pt"); }
        public String toString() { return "Pt(" + x + "," + y + ")"; }
    }

    public static class Fields {
        public int i; public long l; public short s; public byte b; public char c; public boolean z;
        public float f; public double d; public String str; public Object o; public static int si;
        public final int fin; public static final int SFIN = Integer.parseInt("3");
        private int hidden = 4;
        public Fields() { fin = 7; }
    }

    interface Lookup { Object run() throws Exception; }

    static void show(String what, Lookup l) {
        String r;
        try {
            Object v = l.run();
            r = v == null ? "null" : v + " (" + v.getClass().getSimpleName() + ")";
        } catch (InvocationTargetException e) {
            r = "ITE cause=" + e.getCause() + " same=" + (e.getCause() == e.getTargetException()) + " msg=" + e.getMessage();
        } catch (Exception e) {
            r = e.getClass().getName() + ": " + e.getMessage();
        }
        System.out.println(what + " -> " + r);
    }

    static Method m(Class<?> c, String name, Class<?>... p) throws Exception { return c.getMethod(name, p); }

    public static void main(String[] args) throws Exception {
        Calc c = new Calc();
        Method add = m(Calc.class, "add", int.class, int.class);
        show("add", () -> add.invoke(c, 1, 2));
        show("add widen", () -> add.invoke(c, (short) 1, 'a'));
        show("add long", () -> add.invoke(c, 1L, 2));
        show("add double", () -> add.invoke(c, 1.0, 2));
        show("add null arg", () -> add.invoke(c, null, 2));
        show("add few", () -> add.invoke(c, 1));
        show("add none", () -> add.invoke(c));
        show("add many", () -> add.invoke(c, 1, 2, 3));
        show("add null recv", () -> add.invoke(null, 1, 2));
        show("add wrong recv", () -> add.invoke("x", 1, 2));
        show("add string", () -> add.invoke(c, "1", 2));
        Method mul = m(Calc.class, "mul", long.class, long.class);
        show("mul int", () -> mul.invoke(null, 3, 4));
        show("mul ignored recv", () -> mul.invoke("ignored", 2L, 3L));
        Method half = m(Calc.class, "half", double.class);
        show("half int", () -> half.invoke(c, 5));
        show("half float", () -> half.invoke(c, 3f));
        show("half long", () -> half.invoke(c, 9L));
        show("join", () -> m(Calc.class, "join", String.class, Object.class).invoke(c, "a", 5));
        show("join nulls", () -> m(Calc.class, "join", String.class, Object.class).invoke(c, null, null));
        show("join mismatch", () -> m(Calc.class, "join", String.class, Object.class).invoke(c, 1, 2));
        Method secret = Calc.class.getDeclaredMethod("secret", int.class);
        System.out.println(secret.isAccessible());
        secret.setAccessible(true);
        System.out.println(secret.isAccessible());
        show("secret", () -> secret.invoke(c, 4));
        show("boom", () -> m(Calc.class, "boom").invoke(c));
        show("rte", () -> m(Calc.class, "rte").invoke(c));
        show("err", () -> m(Calc.class, "err").invoke(c));
        show("sum array", () -> m(Calc.class, "sum", int[].class).invoke(c, (Object) new int[] {1, 2, 3}));
        show("sum spread", () -> m(Calc.class, "sum", int[].class).invoke(c, 1, 2));
        show("nothing", () -> m(Calc.class, "nothing").invoke(c));
        show("nothing null array", () -> m(Calc.class, "nothing").invoke(c, (Object[]) null));
        show("ch", () -> m(Calc.class, "ch", char.class).invoke(c, 'q'));
        show("byte", () -> m(Calc.class, "b", byte.class).invoke(c, (byte) 7));
        show("byte from int", () -> m(Calc.class, "b", byte.class).invoke(c, 7));
        show("z", () -> m(Calc.class, "z", boolean.class).invoke(c, true));
        show("dispatch class", () -> m(Animal.class, "speak").invoke(new Dog()));
        show("dispatch iface", () -> m(Speaker.class, "speak").invoke(new Dog()));
        show("default method", () -> m(Speaker.class, "twice").invoke(new Dog()));
        show("default via Dog", () -> m(Dog.class, "twice").invoke(new Animal()));
        show("Object.toString", () -> m(Object.class, "toString").invoke(new Dog()));

        show("abstract ctor", () -> Abs.class.getConstructor().newInstance());
        show("Pt", () -> Pt.class.getConstructor(int.class, int.class).newInstance(1, 2));
        show("Pt widen", () -> Pt.class.getConstructor(int.class, int.class).newInstance((short) 3, 'a'));
        show("Pt few", () -> Pt.class.getConstructor(int.class, int.class).newInstance(1));
        show("Pt type", () -> Pt.class.getConstructor(int.class, int.class).newInstance("a", 1));
        Constructor<Pt> priv = Pt.class.getDeclaredConstructor(String.class);
        priv.setAccessible(true);
        show("Pt private", () -> priv.newInstance("four"));
        show("Pt throws", () -> Pt.class.getConstructor().newInstance());
        show("Class.newInstance throws", () -> Pt.class.newInstance());
        show("Class.newInstance ok", () -> Dog.class.newInstance());
        System.out.println(priv + " | " + priv.getName() + " | " + priv.getParameterCount() + " | " + priv.isAccessible());

        Fields o = new Fields();
        Field fi = Fields.class.getField("i"), fl = Fields.class.getField("l"), fs = Fields.class.getField("s");
        Field fb = Fields.class.getField("b"), fc = Fields.class.getField("c"), fz = Fields.class.getField("z");
        Field ff = Fields.class.getField("f"), fd = Fields.class.getField("d"), fstr = Fields.class.getField("str");
        Field fo = Fields.class.getField("o"), fsi = Fields.class.getField("si"), ffin = Fields.class.getField("fin");
        show("set i", () -> { fi.set(o, 5); return fi.get(o); });
        show("setInt i", () -> { fi.setInt(o, 6); return fi.getInt(o); });
        show("set i short", () -> { fi.set(o, (short) 7); return fi.get(o); });
        show("set i char", () -> { fi.set(o, 'a'); return fi.get(o); });
        show("set i long", () -> { fi.set(o, 1L); return fi.get(o); });
        show("setByte i", () -> { fi.setByte(o, (byte) 3); return fi.getLong(o); });
        show("setBoolean i", () -> { fi.setBoolean(o, true); return null; });
        show("getBoolean i", () -> fi.getBoolean(o));
        show("getShort i", () -> fi.getShort(o));
        show("get i double", () -> fi.getDouble(o));
        show("set l int", () -> { fl.set(o, 8); return fl.get(o); });
        show("setLong l", () -> { fl.setLong(o, 1L << 20); return fl.getFloat(o); });
        show("getInt l", () -> fl.getInt(o));
        show("setShort s", () -> { fs.setShort(o, (short) 9); return fs.getInt(o); });
        show("setInt s", () -> { fs.setInt(o, 9); return null; });
        show("set b", () -> { fb.set(o, (byte) 1); return fb.get(o); });
        show("setChar c", () -> { fc.setChar(o, 'x'); return fc.getChar(o); });
        show("setByte c", () -> { fc.setByte(o, (byte) 1); return null; });
        show("set z", () -> { fz.set(o, true); return fz.get(o); });
        show("set z int", () -> { fz.set(o, 1); return null; });
        show("setInt f", () -> { ff.setInt(o, 3); return ff.getFloat(o); });
        show("setLong f", () -> { ff.setLong(o, 4L); return ff.getDouble(o); });
        show("setDouble f", () -> { ff.setDouble(o, 1.5); return null; });
        show("setFloat d", () -> { fd.setFloat(o, 2.5f); return fd.get(o); });
        show("set str", () -> { fstr.set(o, "hi"); return fstr.get(o); });
        show("set str int", () -> { fstr.set(o, 1); return null; });
        show("set str null", () -> { fstr.set(o, null); return fstr.get(o); });
        show("setInt str", () -> { fstr.setInt(o, 1); return null; });
        show("getInt str", () -> fstr.getInt(o));
        show("set o", () -> { fo.set(o, 12); return fo.get(o); });
        show("set i null", () -> { fi.set(o, null); return null; });
        show("static", () -> { fsi.set(null, 21); fsi.setInt("ignored", 22); return fsi.get(null); });
        show("instance null recv", () -> fi.get(null));
        show("instance wrong recv", () -> fi.get("s"));
        show("final set", () -> { ffin.set(o, 1); return null; });
        ffin.setAccessible(true);
        show("final set accessible", () -> { ffin.set(o, 2); return ffin.getInt(o); });
        Field sfin = Fields.class.getField("SFIN");
        sfin.setAccessible(true);
        show("static final set", () -> { sfin.set(null, 1); return null; });
        show("static final setInt null", () -> { sfin.setInt(null, 1); return null; });
        Field hidden = Fields.class.getDeclaredField("hidden");
        hidden.setAccessible(true);
        show("private", () -> { hidden.setInt(o, 40); return hidden.get(o); });
        System.out.println(fi + " | " + sfin + " | " + hidden + " | " + sfin.getType() + " | " + fi.isEnumConstant());
        System.out.println("done");
    }
}
