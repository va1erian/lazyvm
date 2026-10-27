package s4;

import java.lang.reflect.*;
import java.util.function.Function;
import java.util.function.Supplier;

/** Generic signatures read back through java.lang.reflect.Type. */
public class GenericSignatures {
    interface Box<T> { T get(); }
    static class Pair<K, V extends Comparable<V>> implements Comparable<Pair<K, V>> {
        public int compareTo(Pair<K, V> o) { return 0; }
    }
    static class Outer<O> {
        class Inner<I> { }
        static class Nested<N> { }
    }
    static class Plain { }
    enum Color { RED }

    static class Holder<T extends Number> extends Pair<String, Integer> implements Box<T[]>, Function<T, Supplier<? extends T>> {
        public Pair<String, Integer> pair;
        public Pair<String, Integer> pair2;
        public Pair<? extends Number, ? super Integer>[] pairs;
        public Box<?> wild;
        public T[] tarray;
        public Box<T>[][] boxes;
        public Outer<String>.Inner<Integer> inner;
        public Outer.Nested<String> nested;
        public int[][] ints;
        public Box<int[]> boxOfInts;
        public Function<? super T, ? extends Box<? extends T>> fn;
        public Plain plain;
        public Box raw;
        public <Z> Holder(Z z, Box<Z> b) { }
        public T[] get() { return null; }
        public Supplier<? extends T> apply(T t) { return null; }
        public <X extends Exception, Y> Y gen(Box<? extends X> a, X[] b, Y y, int z, String... rest) throws X, java.io.IOException { return y; }
        public static <E extends Enum<E>> E[] enums(Class<E> c) { return null; }
        public <A extends Number & Comparable<A>> void multi(A a) { }
        public void rawParam(Box b, Plain p) { }
    }

    static String describe(Type t) {
        if (t instanceof Class) return "class " + ((Class<?>) t).getName();
        if (t instanceof ParameterizedType) {
            ParameterizedType p = (ParameterizedType) t;
            StringBuilder sb = new StringBuilder("param raw=" + ((Class<?>) p.getRawType()).getSimpleName() + " owner="
                    + (p.getOwnerType() == null ? "none" : p.getOwnerType().getTypeName()) + " args=[");
            Type[] args = p.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) sb.append(i > 0 ? "; " : "").append(describe(args[i]));
            return sb.append("]").toString();
        }
        if (t instanceof TypeVariable) {
            TypeVariable<?> v = (TypeVariable<?>) t;
            StringBuilder sb = new StringBuilder("var " + v.getName() + " of " + decl(v.getGenericDeclaration()) + " bounds=[");
            Type[] b = v.getBounds();
            for (int i = 0; i < b.length; i++) sb.append(i > 0 ? "; " : "").append(b[i].getTypeName());
            return sb.append("]").toString();
        }
        if (t instanceof WildcardType) {
            WildcardType w = (WildcardType) t;
            return "wild upper=" + names(w.getUpperBounds()) + " lower=" + names(w.getLowerBounds());
        }
        return "array of " + describe(((GenericArrayType) t).getGenericComponentType());
    }

    static String decl(GenericDeclaration d) {
        if (d instanceof Class) return ((Class<?>) d).getSimpleName();
        return d instanceof Method ? "method " + ((Method) d).getName() : "ctor";
    }

    static String names(Type[] ts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ts.length; i++) sb.append(i > 0 ? ", " : "").append(ts[i].getTypeName());
        return sb.append("]").toString();
    }

    static void show(String label, Type t) {
        System.out.println(label + ": " + t + " | " + describe(t));
    }

    public static void main(String[] args) throws Exception {
        Class<?> h = Holder.class;
        show("superclass", h.getGenericSuperclass());
        Type[] ifs = h.getGenericInterfaces();
        for (int i = 0; i < ifs.length; i++) show("interface " + i, ifs[i]);
        TypeVariable<?>[] tp = h.getTypeParameters();
        System.out.println(tp.length + " " + tp[0] + " " + names(tp[0].getBounds()) + " " + (tp[0].getGenericDeclaration() == h));
        TypeVariable<?>[] pairParams = Pair.class.getTypeParameters();
        System.out.println(names(pairParams) + " " + names(pairParams[1].getBounds()) + " " + pairParams[1].getName());
        show("pair super", Pair.class.getGenericSuperclass());
        show("pair iface", Pair.class.getGenericInterfaces()[0]);
        show("plain super", Plain.class.getGenericSuperclass());
        System.out.println(Plain.class.getGenericInterfaces().length + " " + Plain.class.getTypeParameters().length + " "
                + (Box.class.getGenericSuperclass() == null) + " " + (Object.class.getGenericSuperclass() == null));
        show("enum super", Color.class.getGenericSuperclass());
        show("inner super", Outer.Inner.class.getGenericSuperclass());
        System.out.println(names(Outer.Inner.class.getTypeParameters()) + " " + names(Outer.class.getTypeParameters()));

        String[] fields = {"pair", "pairs", "wild", "tarray", "boxes", "inner", "nested", "ints", "boxOfInts", "fn", "plain", "raw"};
        for (String f : fields) {
            Field field = h.getField(f);
            show(f, field.getGenericType());
            if (!field.getGenericType().equals(field.getType()) || f.equals("plain")) {
                System.out.println("  type=" + field.getType().getName() + " generic=" + field.toGenericString());
            }
        }
        Type p1 = h.getField("pair").getGenericType(), p2 = h.getField("pair2").getGenericType();
        System.out.println(p1.equals(p2) + " " + (p1.hashCode() == p2.hashCode()) + " " + p1.equals(h.getField("pairs").getGenericType())
                + " " + (p1 != p2) + " " + p1.equals(Pair.class));
        Type tar = h.getField("tarray").getGenericType();
        System.out.println(((GenericArrayType) tar).getGenericComponentType().equals(tp[0]) + " " + tp[0].equals(Pair.class.getTypeParameters()[0]));
        ParameterizedType pt = (ParameterizedType) h.getField("inner").getGenericType();
        System.out.println(pt.getOwnerType() + " | " + pt.getRawType() + " | " + ((ParameterizedType) h.getField("nested").getGenericType()).getOwnerType());

        for (Method m : new Method[] {h.getMethod("gen", Box.class, Exception[].class, Object.class, int.class, String[].class),
                h.getMethod("enums", Class.class), h.getMethod("multi", Number.class), h.getMethod("rawParam", Box.class, Plain.class)}) {
            System.out.println(m.getName() + " " + m.toGenericString());
            System.out.println("  " + m);
            show("  ret", m.getGenericReturnType());
            Type[] ps = m.getGenericParameterTypes();
            for (int i = 0; i < ps.length; i++) show("  param " + i, ps[i]);
            System.out.println("  throws " + names(m.getGenericExceptionTypes()) + " tparams " + names(m.getTypeParameters()));
            TypeVariable<Method>[] mv = m.getTypeParameters();
            for (TypeVariable<Method> v : mv) System.out.println("  " + v.getName() + " decl=" + v.getGenericDeclaration().getName() + " bounds=" + names(v.getBounds()));
        }
        Method get = h.getMethod("get"), apply = h.getMethod("apply", Number.class);
        System.out.println(get.toGenericString() + " | " + apply.toGenericString() + " | " + get.getGenericReturnType());
        Constructor<?> c = h.getConstructor(Object.class, Box.class);
        System.out.println(c.toGenericString() + " | " + c + " | " + names(c.getGenericParameterTypes()) + " | " + names(c.getTypeParameters()));
        System.out.println(h.getMethod("compareTo", Pair.class).toGenericString().length() > 0);
        System.out.println("done");
    }
}
