// VMARGS: -Xmx64m
package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * toArray of streams whose size does not fit an array. The heap is small so that the
 * allocation fails at once; only the class of the error is printed.
 */
public class HugeToArray {
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName(); }
        System.out.println(label + ": " + r);
    }

    public static void main(String[] args) {
        t("long range toArray", () -> LongStream.range(0, Long.MAX_VALUE).toArray());
        t("long range boxed toArray", () -> LongStream.range(0, Long.MAX_VALUE).boxed().toArray());
        t("long range boxed toArray gen", () -> LongStream.range(0, Long.MAX_VALUE).boxed().toArray(Long[]::new));
        t("int range toArray", () -> IntStream.range(0, Integer.MAX_VALUE).toArray());
        t("int rangeClosed toArray", () -> IntStream.rangeClosed(0, Integer.MAX_VALUE).toArray());
        t("int range boxed toArray", () -> IntStream.range(Integer.MIN_VALUE, Integer.MAX_VALUE).boxed().toArray());
        t("int range mapToObj toArray", () -> IntStream.range(0, Integer.MAX_VALUE).mapToObj(x -> x).toArray());
        t("int range mapToLong toArray", () -> IntStream.range(0, Integer.MAX_VALUE).mapToLong(x -> x).toArray());
        t("int range asDouble toArray", () -> IntStream.range(0, Integer.MAX_VALUE).asDoubleStream().toArray());
        t("long range asDouble toArray", () -> LongStream.range(0, Long.MAX_VALUE).asDoubleStream().toArray());
        t("long range map toArray", () -> LongStream.range(0, Long.MAX_VALUE).map(x -> x).toArray());
        t("long range peek toArray", () -> LongStream.range(0, Long.MAX_VALUE).peek(x -> {}).toArray());
        t("long range sorted toArray", () -> LongStream.range(0, Long.MAX_VALUE).sorted().toArray());
        t("int range sorted boxed toArray", () -> IntStream.range(0, Integer.MAX_VALUE).sorted().boxed().toArray());
    }
}
