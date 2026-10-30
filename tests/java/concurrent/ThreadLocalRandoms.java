package concurrent;

import java.util.concurrent.ThreadLocalRandom;

/** ThreadLocalRandom: only deterministic properties (identity, ranges, errors); never a drawn value. */
public class ThreadLocalRandoms {
    interface Body { Object run() throws Throwable; }

    static void show(String label, Body body) {
        try { System.out.println(label + " = " + body.run()); }
        catch (Throwable t) { System.out.println(label + " ! " + t); }
    }

    static boolean allIn(java.util.function.BooleanSupplier check) {
        for (int i = 0; i < 2000; i++) if (!check.getAsBoolean()) return false;
        return true;
    }

    public static void main(String[] args) throws Exception {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        show("same instance", () -> r == ThreadLocalRandom.current());
        show("is Random", () -> (Object) r instanceof java.util.Random);
        Object[] other = new Object[1];
        Thread t = new Thread(() -> other[0] = ThreadLocalRandom.current().nextInt(1));
        t.start();
        t.join();
        show("usable elsewhere", () -> other[0]);
        show("setSeed", () -> { r.setSeed(1); return "no"; });
        show("nextInt 0", () -> r.nextInt(0));
        show("nextInt -1", () -> r.nextInt(-1));
        show("nextInt range bad", () -> r.nextInt(5, 5));
        show("nextInt range bad 2", () -> r.nextInt(6, 5));
        show("nextLong 0", () -> r.nextLong(0));
        show("nextLong range bad", () -> r.nextLong(5, 5));
        show("nextDouble 0", () -> r.nextDouble(0));
        show("nextDouble -1", () -> r.nextDouble(-1.0));
        show("nextDouble NaN", () -> r.nextDouble(Double.NaN));
        show("nextDouble infinite", () -> r.nextDouble(Double.POSITIVE_INFINITY) > 0);
        show("nextDouble range bad", () -> r.nextDouble(5, 5));
        show("nextDouble range NaN", () -> r.nextDouble(Double.NaN, 5));
        show("nextInt 1", () -> r.nextInt(1));
        show("nextLong 1", () -> r.nextLong(1));
        show("nextInt 7..8", () -> r.nextInt(7, 8));
        show("nextLong 7..8", () -> r.nextLong(7, 8));
        show("nextInt bound", () -> allIn(() -> { int v = r.nextInt(10); return v >= 0 && v < 10; }));
        show("nextInt power of two", () -> allIn(() -> { int v = r.nextInt(16); return v >= 0 && v < 16; }));
        show("nextInt range", () -> allIn(() -> { int v = r.nextInt(-5, 5); return v >= -5 && v < 5; }));
        show("nextInt wide range", () -> allIn(() -> { int v = r.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE); return v < Integer.MAX_VALUE; }));
        show("nextLong bound", () -> allIn(() -> { long v = r.nextLong(1L << 40); return v >= 0 && v < 1L << 40; }));
        show("nextLong range", () -> allIn(() -> { long v = r.nextLong(-5, 5); return v >= -5 && v < 5; }));
        show("nextLong wide range", () -> allIn(() -> { long v = r.nextLong(Long.MIN_VALUE, Long.MAX_VALUE); return v < Long.MAX_VALUE; }));
        show("nextDouble", () -> allIn(() -> { double v = r.nextDouble(); return v >= 0 && v < 1; }));
        show("nextDouble bound", () -> allIn(() -> { double v = r.nextDouble(2.5); return v >= 0 && v < 2.5; }));
        show("nextDouble range", () -> allIn(() -> { double v = r.nextDouble(-1.5, 2.5); return v >= -1.5 && v < 2.5; }));
        show("nextFloat", () -> allIn(() -> { float v = r.nextFloat(); return v >= 0 && v < 1; }));
        show("nextGaussian finite", () -> allIn(() -> { double v = r.nextGaussian(); return !Double.isNaN(v) && !Double.isInfinite(v); }));
        show("nextBoolean both", () -> {
            boolean a = false, b = false;
            for (int i = 0; i < 2000; i++) { if (r.nextBoolean()) a = true; else b = true; }
            return a && b;
        });
        show("nextBytes", () -> { byte[] x = new byte[8]; r.nextBytes(x); return x.length; });
        show("nextBytes null", () -> { r.nextBytes(null); return "no"; });
        show("varies", () -> {
            int first = r.nextInt();
            for (int i = 0; i < 100; i++) if (r.nextInt() != first) return true;
            return false;
        });
    }
}
