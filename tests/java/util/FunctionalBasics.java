package util;

import java.util.Comparator;
import java.util.function.*;

/** Defaults and statics of java.util.function, including null argument checks. */
public class FunctionalBasics {
    static void npe(String what, Runnable r) {
        try { r.run(); System.out.println(what + ": no exception"); }
        catch (Exception e) { System.out.println(what + ": " + e.getClass().getName()); }
    }

    public static void main(String[] args) {
        Function<Integer, Integer> inc = x -> x + 1, dbl = x -> x * 2;
        System.out.println(inc.andThen(dbl).apply(5) + " " + inc.compose(dbl).apply(5) + " " + Function.<String>identity().apply("id"));
        BiFunction<Integer, Integer, Integer> add = (a, b) -> a + b;
        System.out.println(add.andThen(dbl).apply(2, 3));
        Predicate<Integer> pos = x -> x > 0, even = x -> x % 2 == 0;
        System.out.println(pos.and(even).test(4) + " " + pos.and(even).test(3) + " " + pos.or(even).test(-2) + " " + pos.negate().test(1));
        System.out.println(Predicate.isEqual("a").test("a") + " " + Predicate.isEqual(null).test(null) + " " + Predicate.isEqual("a").test(null));
        BiPredicate<String, Integer> bp = (s, n) -> s.length() == n, tr = (s, n) -> true;
        System.out.println(bp.and(tr).test("ab", 2) + " " + bp.or(tr).test("ab", 3) + " " + bp.negate().test("ab", 2));
        StringBuilder sb = new StringBuilder();
        Consumer<String> c1 = s -> sb.append("1").append(s), c2 = s -> sb.append("2").append(s);
        c1.andThen(c2).accept("x");
        BiConsumer<String, Integer> b1 = (s, n) -> sb.append(s).append(n), b2 = (s, n) -> sb.append(n).append(s);
        b1.andThen(b2).accept("y", 3);
        System.out.println(sb);
        UnaryOperator<String> up = String::trim;
        System.out.println(UnaryOperator.<String>identity().apply("u") + up.apply(" t ") + "|");
        Comparator<String> byLen = (a, b) -> a.length() - b.length();
        System.out.println(BinaryOperator.minBy(byLen).apply("aaa", "b") + BinaryOperator.maxBy(byLen).apply("aaa", "b")
            + BinaryOperator.minBy(byLen).apply("x", "y") + BinaryOperator.maxBy(byLen).apply("x", "y"));
        IntUnaryOperator iu = x -> x + 1, iu2 = x -> x * 3;
        System.out.println(iu.andThen(iu2).applyAsInt(1) + " " + iu.compose(iu2).applyAsInt(1) + " " + IntUnaryOperator.identity().applyAsInt(9));
        LongUnaryOperator lu = x -> x + 1, lu2 = x -> x * 3;
        System.out.println(lu.andThen(lu2).applyAsLong(1) + " " + lu.compose(lu2).applyAsLong(1) + " " + LongUnaryOperator.identity().applyAsLong(9));
        DoubleUnaryOperator du = x -> x + 1, du2 = x -> x * 3;
        System.out.println(du.andThen(du2).applyAsDouble(1) + " " + du.compose(du2).applyAsDouble(1) + " " + DoubleUnaryOperator.identity().applyAsDouble(9));
        IntPredicate ip = x -> x > 1; LongPredicate lp = x -> x > 1; DoublePredicate dp = x -> x > 1;
        System.out.println(ip.and(x -> x < 5).test(3) + " " + ip.or(x -> x < 0).test(0) + " " + ip.negate().test(0)
            + " " + lp.and(x -> x < 5).test(9) + " " + lp.or(x -> x < 0).test(-1) + " " + lp.negate().test(2)
            + " " + dp.and(x -> x < 5).test(2) + " " + dp.or(x -> x < 0).test(0) + " " + dp.negate().test(0));
        StringBuilder t = new StringBuilder();
        IntConsumer ic = x -> t.append('i').append(x);
        LongConsumer lc = x -> t.append('l').append(x);
        DoubleConsumer dc = x -> t.append('d').append(x);
        ic.andThen(x -> t.append('!')).accept(1);
        lc.andThen(x -> t.append('!')).accept(2);
        dc.andThen(x -> t.append('!')).accept(3);
        ObjIntConsumer<String> oi = (s, n) -> t.append(s).append(n);
        ObjLongConsumer<String> ol = (s, n) -> t.append(s).append(n);
        ObjDoubleConsumer<String> od = (s, n) -> t.append(s).append(n);
        oi.accept("a", 1); ol.accept("b", 2L); od.accept("c", 3.5);
        System.out.println(t);
        Supplier<String> sup = () -> "s"; BooleanSupplier bs = () -> true; IntSupplier is = () -> 1; LongSupplier ls = () -> 2L; DoubleSupplier ds = () -> 3.0;
        System.out.println(sup.get() + bs.getAsBoolean() + is.getAsInt() + ls.getAsLong() + ds.getAsDouble());
        IntFunction<String> inf = n -> "i" + n; LongFunction<String> lf = n -> "l" + n; DoubleFunction<String> df = n -> "d" + n;
        ToIntFunction<String> tif = String::length; ToLongFunction<String> tlf = s -> s.length() * 2L; ToDoubleFunction<String> tdf = s -> s.length() / 2.0;
        ToIntBiFunction<String, String> tib = (a, b) -> a.length() + b.length();
        ToLongBiFunction<String, String> tlb = (a, b) -> (long) a.length() * b.length();
        ToDoubleBiFunction<String, String> tdb = (a, b) -> a.length() / (double) b.length();
        System.out.println(inf.apply(1) + lf.apply(2) + df.apply(3) + " " + tif.applyAsInt("ab") + " " + tlf.applyAsLong("ab") + " " + tdf.applyAsDouble("abc")
            + " " + tib.applyAsInt("a", "bc") + " " + tlb.applyAsLong("ab", "cd") + " " + tdb.applyAsDouble("a", "bc"));
        IntToLongFunction i2l = x -> x * 10L; IntToDoubleFunction i2d = x -> x / 4.0; LongToIntFunction l2i = x -> (int) x; LongToDoubleFunction l2d = x -> x / 4.0;
        DoubleToIntFunction d2i = x -> (int) x; DoubleToLongFunction d2l = x -> (long) x;
        System.out.println(i2l.applyAsLong(3) + " " + i2d.applyAsDouble(3) + " " + l2i.applyAsInt(1L << 33 | 5) + " " + l2d.applyAsDouble(3) + " " + d2i.applyAsInt(3.9) + " " + d2l.applyAsLong(-3.9));
        IntBinaryOperator ib = (a, b) -> a - b; LongBinaryOperator lb = (a, b) -> a - b; DoubleBinaryOperator db = (a, b) -> a - b;
        System.out.println(ib.applyAsInt(5, 2) + " " + lb.applyAsLong(5, 2) + " " + db.applyAsDouble(5, 2));
        npe("Function.andThen", () -> inc.andThen(null));
        npe("Function.compose", () -> inc.compose(null));
        npe("BiFunction.andThen", () -> add.andThen(null));
        npe("Predicate.and", () -> pos.and(null));
        npe("Predicate.or", () -> pos.or(null));
        npe("BiPredicate.and", () -> bp.and(null));
        npe("BiPredicate.or", () -> bp.or(null));
        npe("Consumer.andThen", () -> c1.andThen(null));
        npe("BiConsumer.andThen", () -> b1.andThen(null));
        npe("IntConsumer.andThen", () -> ic.andThen(null));
        npe("LongConsumer.andThen", () -> lc.andThen(null));
        npe("DoubleConsumer.andThen", () -> dc.andThen(null));
        npe("IntPredicate.and", () -> ip.and(null));
        npe("LongPredicate.or", () -> lp.or(null));
        npe("DoublePredicate.and", () -> dp.and(null));
        npe("IntUnaryOperator.andThen", () -> iu.andThen(null));
        npe("LongUnaryOperator.compose", () -> lu.compose(null));
        npe("DoubleUnaryOperator.compose", () -> du.compose(null));
        npe("minBy", () -> BinaryOperator.minBy(null));
        npe("maxBy", () -> BinaryOperator.maxBy(null));
        System.out.println("done");
    }
}
