package s4;

import java.util.function.*;

/** Every kind of method reference, including adaptation of boxing, widening and varargs. */
public class MethodRefs {
    static class Base { String who() { return "base"; } }
    static class Derived extends Base {
        String who() { return "derived"; }
        Supplier<String> sup() { return super::who; }
        Supplier<String> self() { return this::who; }
    }
    interface Shape {
        default String kind() { return "shape"; }
        static String make(int n) { return "made" + n; }
    }
    static class Sq implements Shape {
        Supplier<String> k() { return Shape.super::kind; }
    }
    static long twice(int x) { return x * 2L; }
    static String show(long x) { return "long:" + x; }
    static int sum(int... xs) { int t = 0; for (int x : xs) t += x; return t; }
    static String join(String sep, Object... xs) { StringBuilder sb = new StringBuilder(); for (Object x : xs) sb.append(x).append(sep); return sb.toString(); }
    private static String priv(String s) { return "priv " + s; }
    private String ipriv(String s) { return "ipriv " + s + tag; }
    String tag = "!";
    static double half(double d) { return d / 2; }

    public static void main(String[] args) {
        Function<String, Integer> st = Integer::parseInt;
        System.out.println(st.apply("42") + 1);
        String str = "hello";
        IntSupplier bound = str::length;
        ToIntFunction<String> unbound = String::length;
        System.out.println(bound.getAsInt() + " " + unbound.applyAsInt("abc"));
        Supplier<StringBuilder> ctor = StringBuilder::new;
        System.out.println(ctor.get().append("sb").toString());
        Function<String, StringBuilder> ctor1 = StringBuilder::new;
        System.out.println(ctor1.apply("init").reverse());
        IntFunction<int[]> arr = int[]::new;
        Function<Integer, String[]> sarr = String[]::new;
        System.out.println(arr.apply(3).length + " " + sarr.apply(2).length);
        Derived dv = new Derived();
        System.out.println(dv.sup().get() + " " + dv.self().get());
        System.out.println(new Sq().k().get());
        IntFunction<Long> boxed = MethodRefs::twice;
        System.out.println(boxed.apply(21));
        ToLongFunction<Integer> unb = Integer::intValue;
        System.out.println(unb.applyAsLong(7));
        Function<Integer, Object> wide = MethodRefs::show;
        System.out.println(wide.apply(5));
        DoubleUnaryOperator du = MethodRefs::half;
        IntToDoubleFunction itd = MethodRefs::half;
        System.out.println(du.applyAsDouble(5) + " " + itd.applyAsDouble(9));
        IntBinaryOperator va = MethodRefs::sum;
        BiFunction<Integer, Integer, Integer> va2 = MethodRefs::sum;
        Supplier<Integer> va0 = MethodRefs::sum;
        System.out.println(va.applyAsInt(3, 4) + " " + va2.apply(5, 6) + " " + va0.get());
        BiFunction<String, String, String> j = MethodRefs::join;
        System.out.println(j.apply("-", "x"));
        Function<Integer, String> ifn = Shape::make;
        System.out.println(ifn.apply(8));
        Function<String, String> p = MethodRefs::priv;
        MethodRefs m = new MethodRefs();
        Function<String, String> ip = m::ipriv;
        BiFunction<MethodRefs, String, String> ipu = MethodRefs::ipriv;
        System.out.println(p.apply("a") + "; " + ip.apply("b") + "; " + ipu.apply(m, "c"));
        Supplier<Integer> hc = "x"::hashCode;
        Function<Object, String> ts = Object::toString;
        BiPredicate<String, String> eq = String::equalsIgnoreCase;
        System.out.println(hc.get() + " " + ts.apply(12) + " " + eq.test("A", "a"));
        Comparable<String> cmp = "b"::compareTo;
        System.out.println(cmp.compareTo("a"));
        try {
            String nul = null;
            Supplier<Integer> bad = nul::length;
            System.out.println("no npe");
        } catch (NullPointerException e) {
            System.out.println("npe at bind");
        }
        Consumer<Object> pr = System.out::println;
        pr.accept("printed");
        System.out.println("done");
    }
}
