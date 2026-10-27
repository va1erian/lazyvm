// ORACLE: modern
package s1;

public class FloatMath {
    static double nan() { return 0.0 / zero(); }
    static double zero() { return 0.0; }
    static double negZero() { return -zero(); }

    static void cmpd(String name, double a, double b) {
        System.out.println(name + ": < " + (a < b) + " <= " + (a <= b) + " > " + (a > b)
            + " >= " + (a >= b) + " == " + (a == b) + " != " + (a != b));
    }

    static void cmpf(String name, float a, float b) {
        System.out.println(name + ": < " + (a < b) + " <= " + (a <= b) + " > " + (a > b)
            + " >= " + (a >= b) + " == " + (a == b) + " != " + (a != b));
    }

    static void conv(String name, double d) {
        System.out.println(name + ": d2i=" + (int) d + " d2l=" + (long) d
            + " f2i=" + (int) (float) d + " f2l=" + (long) (float) d);
    }

    public static void main(String[] args) {
        double a = 0.1, b = 0.2;
        System.out.println("0.1+0.2 = " + (a + b));
        System.out.println("0.1*3 = " + (a * 3));
        System.out.println("1/3 = " + (1.0 / (args.length + 3)));
        System.out.println("2/3f = " + (2.0f / (args.length + 3)));
        System.out.println("0.1f+0.2f = " + ((float) a + (float) b));
        System.out.println("1e10 = " + (1e10 + args.length));
        System.out.println("1e-5 = " + (1e-5 + args.length));
        System.out.println("100.0 = " + (100.0 + args.length));
        System.out.println("1.0E7 = " + (1.0E7 + args.length));
        System.out.println("9999999.0 = " + (9999999.0 + args.length));
        System.out.println("0.001 = " + (0.001 + args.length));
        System.out.println("0.0001 = " + (0.0001 + args.length));
        System.out.println("123456789.0f = " + (123456789.0f + args.length));
        System.out.println("1.5f = " + (1.5f + args.length));
        System.out.println("1.0E10f = " + (1.0E10f + args.length));
        System.out.println("max double = " + Double.MAX_VALUE);
        System.out.println("min double = " + Double.MIN_VALUE);
        System.out.println("max float = " + Float.MAX_VALUE);
        System.out.println("min float = " + Float.MIN_VALUE);
        System.out.println("float to double 0.1f = " + (double) (float) a);
        System.out.println("float to double 16777217 = " + (double) (float) (16777217 + args.length));
        System.out.println("long to float = " + (float) (123456789012L + args.length));
        System.out.println("long to double = " + (double) (Long.MAX_VALUE - args.length));
        System.out.println("int to float = " + (float) (Integer.MAX_VALUE - args.length));
        System.out.println("double to float 1e40 = " + (float) (1e40 + args.length));
        System.out.println("double to float 1e-50 = " + (float) (1e-50 + args.length));

        double nan = nan();
        double inf = 1.0 / zero();
        double ninf = -1.0 / zero();
        System.out.println("nan = " + nan + " inf = " + inf + " -inf = " + ninf);
        cmpd("nan vs nan", nan, nan);
        cmpd("nan vs 1", nan, 1.0);
        cmpd("1 vs nan", 1.0, nan);
        cmpd("0.0 vs -0.0", zero(), negZero());
        cmpd("inf vs max", inf, Double.MAX_VALUE);
        cmpf("fnan vs fnan", (float) nan, (float) nan);
        cmpf("fnan vs 2", (float) nan, 2.0f);
        cmpf("2 vs fnan", 2.0f, (float) nan);
        cmpf("1.5 vs 2.5", 1.5f, 2.5f);
        System.out.println("nan != nan " + (nan != nan));
        System.out.println("isNaN " + Double.isNaN(nan) + " " + Double.isNaN(1.0));
        System.out.println("inf-inf = " + (inf - inf) + ", inf*0 = " + (inf * zero()));

        System.out.println("1/0.0 = " + (1.0 / zero()));
        System.out.println("1/-0.0 = " + (1.0 / negZero()));
        System.out.println("-0.0 prints " + negZero());
        System.out.println("0.0 == -0.0 " + (zero() == negZero()));
        System.out.println("0.0 + -0.0 = " + (zero() + negZero()));
        System.out.println("-0.0 + -0.0 = " + (negZero() + negZero()));
        System.out.println("-0.0f prints " + (-(float) zero()));
        System.out.println("0 * -1.0 = " + (zero() * -1.0));

        conv("1e10", 1e10 + args.length);
        conv("-1e10", -1e10 - args.length);
        conv("1e30", 1e30 + args.length);
        conv("-1e30", -1e30 - args.length);
        conv("nan", nan);
        conv("inf", inf);
        conv("-inf", ninf);
        conv("2.9", 2.9 + args.length);
        conv("-2.9", -2.9 - args.length);
        conv("2147483647.5", 2147483647.5 + args.length);
        conv("9.3e18", 9.3e18 + args.length);
        System.out.println("d2i 2^31 = " + (int) (2147483648.0 + args.length));
        System.out.println("d2l 2^63 = " + (long) (9223372036854775808.0 + args.length));
        System.out.println("d2i -0.9 = " + (int) (-0.9 - args.length));
        System.out.println("f2i 3e9f = " + (int) (3e9f + args.length));

        System.out.println("5.5 % 2 = " + (5.5 + args.length) % 2);
        System.out.println("-5.5 % 2 = " + (-5.5 - args.length) % 2);
        System.out.println("5.5 % -2 = " + (5.5 + args.length) % -2);
        System.out.println("-5.5 % -2 = " + (-5.5 - args.length) % -2);
        System.out.println("5 % 0.0 = " + (5.0 + args.length) % zero());
        System.out.println("inf % 3 = " + inf % 3);
        System.out.println("3 % inf = " + (3.0 + args.length) % inf);
        System.out.println("-6.0 % 3 = " + (-6.0 - args.length) % 3);
        System.out.println("5.5f % 2 = " + (5.5f + args.length) % 2f);
        System.out.println("-7.25f % 2.5f = " + (-7.25f - args.length) % 2.5f);

        float f = 1.1f;
        double d = f;
        System.out.println("1.1f widened = " + d);
        System.out.println("1.1f * 1.1f = " + (f * f));
        System.out.println("1.1 * 1.1 = " + (1.1 * (1.1 + args.length)));
        System.out.println("float sum = " + sumf());
        System.out.println("double sum = " + sumd());
        System.out.println("neg = " + (-d) + " " + (-f));
        double acc = 1;
        for (int i = 0; i < 10; i++) acc = acc * 1.5 - 0.25;
        System.out.println("iter = " + acc);
        System.out.println("done");
    }

    static float sumf() {
        float s = 0;
        for (int i = 0; i < 10; i++) s += 0.1f;
        return s;
    }

    static double sumd() {
        double s = 0;
        for (int i = 0; i < 10; i++) s += 0.1;
        return s;
    }
}
