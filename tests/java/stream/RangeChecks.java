package stream;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/** The order in which Arrays.stream, Arrays.spliterator and Spliterators.spliterator check a range. */
public class RangeChecks {
    static void t(String label, Supplier<Object> s) {
        Object r;
        try { r = s.get(); } catch (Throwable e) { r = "!" + e.getClass().getName() + (e.getMessage() == null ? "" : ": " + e.getMessage()); }
        System.out.println(label + ": " + r);
    }

    public static void main(String[] args) {
        String[] o = {"a", "b", "c"};
        int[] i = {1, 2, 3};
        long[] l = {1, 2, 3};
        double[] d = {1, 2, 3};
        int[][] ranges = {
            {-1, -2}, {-1, -5}, {-3, -1}, {-1, 0}, {-1, 2}, {-1, 3}, {-1, 4}, {-1, 7},
            {5, 3}, {5, 4}, {4, 3}, {4, 2}, {7, 5}, {4, 4}, {5, 5}, {3, 3}, {3, 2}, {0, 4}, {0, 7}, {2, 4}, {3, 4}, {4, 5}, {4, 7},
            {Integer.MAX_VALUE, Integer.MIN_VALUE}, {Integer.MIN_VALUE, Integer.MAX_VALUE}, {Integer.MIN_VALUE, -1}, {Integer.MIN_VALUE, Integer.MIN_VALUE},
            {0, Integer.MAX_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {1, Integer.MIN_VALUE}, {Integer.MIN_VALUE, 0},
        };
        for (int[] r : ranges) {
            int from = r[0], to = r[1];
            String k = "[" + from + "," + to + ")";
            t("Stream " + k, () -> Arrays.stream(o, from, to).count());
            t("IntStream " + k, () -> Arrays.stream(i, from, to).count());
            t("LongStream " + k, () -> Arrays.stream(l, from, to).count());
            t("DoubleStream " + k, () -> Arrays.stream(d, from, to).count());
            t("Arrays.spliterator Object " + k, () -> Arrays.spliterator(o, from, to).estimateSize());
            t("Arrays.spliterator int " + k, () -> Arrays.spliterator(i, from, to).estimateSize());
            t("Arrays.spliterator long " + k, () -> Arrays.spliterator(l, from, to).estimateSize());
            t("Arrays.spliterator double " + k, () -> Arrays.spliterator(d, from, to).estimateSize());
            t("Spliterators Object " + k, () -> Spliterators.spliterator(o, from, to, 0).estimateSize());
            t("Spliterators int " + k, () -> Spliterators.spliterator(i, from, to, 0).estimateSize());
            t("Spliterators long " + k, () -> Spliterators.spliterator(l, from, to, 0).estimateSize());
            t("Spliterators double " + k, () -> Spliterators.spliterator(d, from, to, 0).estimateSize());
        }
        // an empty array and a null array
        t("Stream empty array [0,0)", () -> Arrays.stream(new String[0], 0, 0).count());
        t("Stream empty array [0,1)", () -> Arrays.stream(new String[0], 0, 1).count());
        t("Stream empty array [-1,0)", () -> Arrays.stream(new String[0], -1, 0).count());
        t("Stream empty array [1,0)", () -> Arrays.stream(new String[0], 1, 0).count());
        t("Stream null array [0,0)", () -> Arrays.stream((String[]) null, 0, 0).count());
        t("IntStream null array [1,0)", () -> Arrays.stream((int[]) null, 1, 0).count());
        t("Arrays.spliterator null array [-1,0)", () -> Arrays.spliterator((int[]) null, -1, 0).estimateSize());
        t("Spliterators null array [5,3)", () -> Spliterators.spliterator((Object[]) null, 5, 3, 0).estimateSize());
    }
}
