package s4;

import java.util.function.*;

/** Lambda forms: capture, this, nesting, initializers, bridges, serializable, intersections. */
public class Lambdas {
    interface F<T> { T get(); }
    interface G extends F<String> { String get(); }
    interface Marker {}
    interface Greeter {
        String name();
        default Supplier<String> greet() { return () -> "hi " + name(); }
    }

    static final Supplier<String> STATIC_INIT;
    static { STATIC_INIT = () -> "static-init"; }
    final Supplier<String> instInit;
    { instInit = () -> "inst-init " + this.tag; }
    String tag = "T";
    static Function<Integer, Integer> fact;

    String viaThis() {
        Supplier<String> s = () -> "this:" + tag;
        tag = "T2";
        return s.get();
    }

    public static void main(String[] args) throws Exception {
        Supplier<String> plain = () -> "plain";
        System.out.println(plain.get());
        byte b = 1; short sh = 2; char c = 'c'; int i = 4; long l = 5L;
        float f = 6.5f; double d = 7.25; boolean z = true; String s = "str"; Object o = new int[]{9};
        Supplier<String> all = () -> b + "," + sh + "," + c + "," + i + "," + l + "," + f + "," + d + "," + z + "," + s + "," + ((int[]) o)[0];
        System.out.println(all.get());
        IntSupplier is = () -> i * 2;
        LongSupplier ls = () -> l << 33;
        DoubleSupplier ds = () -> d / 2;
        BooleanSupplier bs = () -> !z;
        System.out.println(is.getAsInt() + " " + ls.getAsLong() + " " + ds.getAsDouble() + " " + bs.getAsBoolean());
        Runnable r = () -> System.out.println("void lambda " + s);
        r.run();
        Function<Integer, Function<Integer, Function<Integer, Integer>>> curried = x -> y -> w -> x * 100 + y * 10 + w + i;
        System.out.println(curried.apply(1).apply(2).apply(3));
        Lambdas inst = new Lambdas();
        System.out.println(inst.viaThis());
        System.out.println(STATIC_INIT.get() + " / " + inst.instInit.get());
        fact = n -> n <= 1 ? 1 : n * fact.apply(n - 1);
        System.out.println(fact.apply(10));
        Greeter g = () -> "bob";
        System.out.println(g.greet().get());
        F<String> fs = () -> "F";
        G gg = () -> "G";
        F<String> viaG = gg;
        System.out.println(fs.get() + viaG.get() + gg.get());
        G g2 = new G() { public String get() { return "anon"; } };
        System.out.println(((F<String>) g2).get());
        Runnable ser = (Runnable & java.io.Serializable) () -> System.out.println("serializable run");
        ser.run();
        System.out.println(ser instanceof java.io.Serializable);
        Runnable mk = (Runnable & Marker) () -> System.out.println("marker run");
        mk.run();
        System.out.println((mk instanceof Marker) + " " + (plain instanceof Marker));
        BiFunction<Long, Double, String> bf = (x, y) -> x + y + "";
        System.out.println(bf.apply(3L, 0.5));
        ToIntFunction<String> len = String::length;
        IntBinaryOperator add = (x, y) -> x + y;
        System.out.println(len.applyAsInt("four") + add.applyAsInt(1, 2));
        Supplier<Object> lam = () -> new Object() { public String toString() { return "anon-in-lambda"; } };
        System.out.println(lam.get());
        try {
            Supplier<String> bad = () -> s.substring(10);
            bad.get();
        } catch (StringIndexOutOfBoundsException e) {
            System.out.println("caught " + e.getClass().getName());
        }
        System.out.println("done");
    }
}
