package s4;

import java.lang.reflect.*;

/** Class metadata, member lists and lookups. Lists are sorted here because their order is unspecified. */
public class ReflectBasics {
    interface Shape { double area(); default String label() { return "shape"; } String NAME = "S"; }
    interface Named { String name(); }

    static abstract class Base implements Shape, Named {
        protected int id;
        public static int count;
        public abstract String name();
        public String describe() { return name(); }
        private void hidden() { }
    }

    public static final class Square extends Base {
        public final double side;
        private static final String TAG = "sq";
        public Square(double s) { side = s; }
        Square() { this(1); }
        protected Square(int a, String b) { this(a); }
        public double area() { return side * side; }
        public String name() { return "square"; }
        static void util() { }
        public void many(int a, long[] b, String... c) throws java.io.IOException, IllegalStateException { }
        public static synchronized int sum(int a, int b) { return a + b; }
    }

    static void sorted(String title, Object[] items) {
        String[] s = new String[items.length];
        int n = 0;
        for (Object o : items) {
            if (o instanceof Method && ((Method) o).isSynthetic()) continue;
            if (o instanceof Field && ((Field) o).isSynthetic()) continue;
            s[n++] = String.valueOf(o);
        }
        for (int i = 1; i < n; i++) {
            String k = s[i];
            int j = i - 1;
            while (j >= 0 && s[j].compareTo(k) > 0) { s[j + 1] = s[j]; j--; }
            s[j + 1] = k;
        }
        System.out.println(title + " (" + n + ")");
        for (int i = 0; i < n; i++) System.out.println("  " + s[i]);
    }

    static Method[] noObject(Method[] ms) {
        int n = 0;
        Method[] out = new Method[ms.length];
        for (Method m : ms) if (m.getDeclaringClass() != Object.class) out[n++] = m;
        Method[] r = new Method[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    interface Lookup { Object run() throws Exception; }

    static void attempt(String what, Lookup l) {
        try {
            System.out.println(what + " -> " + l.run());
        } catch (Exception e) {
            System.out.println(what + " threw " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> sq = Square.class;
        System.out.println(sq.getName() + " | " + sq.getSimpleName() + " | " + Modifier.toString(sq.getModifiers()));
        System.out.println(Modifier.toString(Base.class.getModifiers()) + " | " + Modifier.toString(Shape.class.getModifiers()));
        System.out.println(sq.getSuperclass().getName() + " | " + sq.getDeclaringClass().getName() + " | " + sq.isMemberClass());
        Class<?>[] ifs = Base.class.getInterfaces();
        System.out.println(ifs.length + " " + ifs[0].getSimpleName() + " " + ifs[1].getSimpleName());
        System.out.println(ReflectBasics.class.getDeclaringClass() + " " + ReflectBasics.class.isMemberClass());
        class Local { }
        System.out.println(new Object() { }.getClass().isAnonymousClass() + " " + Local.class.isAnonymousClass()
                + " " + Local.class.isLocalClass() + " " + sq.isAnonymousClass() + " " + sq.isLocalClass());
        System.out.println(int.class.isPrimitive() + " " + int[].class.getName() + " " + int[].class.getSimpleName()
                + " " + int[].class.getComponentType() + " " + Shape.class.isInterface());

        sorted("declared fields", sq.getDeclaredFields());
        sorted("declared methods", sq.getDeclaredMethods());
        sorted("declared constructors", sq.getDeclaredConstructors());
        sorted("public constructors", sq.getConstructors());
        sorted("public fields", sq.getFields());
        sorted("public methods", noObject(sq.getMethods()));
        sorted("base declared methods", Base.class.getDeclaredMethods());
        sorted("shape methods", Shape.class.getMethods());
        sorted("shape fields", Shape.class.getFields());

        attempt("getField side", () -> sq.getField("side"));
        attempt("getField NAME", () -> sq.getField("NAME"));
        attempt("getField count", () -> sq.getField("count").getDeclaringClass().getSimpleName());
        attempt("getField TAG", () -> sq.getField("TAG"));
        attempt("getDeclaredField TAG", () -> sq.getDeclaredField("TAG"));
        attempt("getDeclaredField id", () -> sq.getDeclaredField("id"));
        attempt("getMethod area", () -> sq.getMethod("area"));
        attempt("getMethod label", () -> sq.getMethod("label").getDeclaringClass().getSimpleName());
        attempt("getMethod many", () -> sq.getMethod("many", int.class, long[].class, String[].class));
        attempt("getMethod many bad", () -> sq.getMethod("many", int.class, long[].class));
        attempt("getMethod util", () -> sq.getMethod("util"));
        attempt("getMethod hidden", () -> sq.getMethod("hidden"));
        attempt("getDeclaredMethod hidden", () -> Base.class.getDeclaredMethod("hidden"));
        attempt("getDeclaredMethod util", () -> sq.getDeclaredMethod("util"));
        attempt("getDeclaredMethod describe", () -> sq.getDeclaredMethod("describe"));
        attempt("getConstructor double", () -> sq.getConstructor(double.class));
        attempt("getConstructor none", () -> sq.getConstructor());
        attempt("getDeclaredConstructor none", () -> sq.getDeclaredConstructor());
        attempt("getDeclaredConstructor int,String", () -> sq.getDeclaredConstructor(int.class, String.class));
        attempt("getDeclaredConstructor String", () -> sq.getDeclaredConstructor(String.class));
        attempt("getMethod null", () -> sq.getMethod(null));
        attempt("newInstance", () -> ((Square) sq.newInstance()).side);
        attempt("interface newInstance", () -> Shape.class.newInstance());

        Method many = sq.getMethod("many", int.class, long[].class, String[].class);
        System.out.println(many.getReturnType() + " " + many.getParameterCount() + " " + many.isVarArgs()
                + " " + many.getExceptionTypes().length + " " + many.getName() + " " + many.getDeclaringClass().getSimpleName());
        System.out.println(java.util.Arrays.toString(many.getParameterTypes()));
        System.out.println(java.util.Arrays.toString(many.getParameters()));
        System.out.println(many.getParameters()[0].getName() + " " + many.getParameters()[2].isVarArgs() + " "
                + many.getParameters()[2].getType().getSimpleName());
        System.out.println(many.equals(sq.getMethod("many", int.class, long[].class, String[].class))
                + " " + (many.hashCode() == sq.getMethod("many", int.class, long[].class, String[].class).hashCode())
                + " " + many.equals(sq.getMethod("area")));
        Method[] a = sq.getDeclaredMethods();
        a[0] = null;
        System.out.println(sq.getDeclaredMethods()[0] != null);
        Method sum = sq.getMethod("sum", int.class, int.class);
        System.out.println(Modifier.toString(sum.getModifiers()) + " | " + Modifier.isStatic(sum.getModifiers())
                + " " + Modifier.isSynchronized(sum.getModifiers()) + " " + Modifier.isAbstract(sum.getModifiers())
                + " | " + Modifier.toString(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL | Modifier.TRANSIENT
                | Modifier.VOLATILE | Modifier.NATIVE | Modifier.STRICT | Modifier.INTERFACE | Modifier.PRIVATE)
                + " | [" + Modifier.toString(0) + "] " + Modifier.isProtected(4) + Modifier.isPrivate(2));
        Method label = sq.getMethod("label");
        System.out.println(label.isDefault() + " " + Shape.class.getMethod("area").isDefault() + " " + many.isDefault()
                + " " + label.toGenericString());
        System.out.println("done");
    }
}
