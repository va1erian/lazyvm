package s4;

import java.util.function.*;

/** What lambdas capture: values at creation time, arrays by reference, loop variables. */
public class Capture {
    static int counter;
    int field = 1;

    Supplier<Integer> field() { return () -> field; }

    public static void main(String[] args) {
        int x = 10;
        Supplier<Integer> s = () -> x + 1;
        System.out.println(s.get());
        Runnable[] rs = new Runnable[4];
        for (int i = 0; i < rs.length; i++) {
            final int j = i * i;
            rs[i] = () -> System.out.println("run " + j);
        }
        for (Runnable r : rs) r.run();
        for (int k : new int[]{7, 8}) {
            Runnable r = () -> System.out.println("each " + k);
            r.run();
        }
        int[] box = {0};
        Runnable inc = () -> box[0]++;
        inc.run(); inc.run(); inc.run();
        System.out.println(box[0]);
        box[0] = 100;
        IntSupplier read = () -> box[0];
        box[0] = 200;
        System.out.println(read.getAsInt());
        Capture c = new Capture();
        Supplier<Integer> f = c.field();
        c.field = 5;
        System.out.println(f.get());
        Runnable st = () -> counter += 3;
        st.run(); st.run();
        System.out.println(counter);
        String a = "a";
        String b = a + "b";
        Supplier<String> sa = () -> a + b;
        Supplier<Supplier<String>> nested = () -> () -> a + b + x;
        System.out.println(sa.get() + " " + nested.get().get());
        long big = 1L << 40;
        double dd = 2.5;
        Supplier<Double> mix = () -> big + dd + x;
        System.out.println(mix.get());
        StringBuilder sb = new StringBuilder();
        Consumer<String> app = sb::append;
        app.accept("p"); app.accept("q");
        sb.append("r");
        System.out.println(sb);
        System.out.println("done");
    }
}
