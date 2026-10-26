package lang;

public class MathFns {
    static void p(String label, Object v) { System.out.println(label + " = " + v); }

    static double nan() { return 0.0 / zero(); }
    static double zero() { return 0.0; }

    static void exact(String label, int a, int b, char op) {
        try {
            int r = op == '+' ? Math.addExact(a, b) : op == '-' ? Math.subtractExact(a, b) : Math.multiplyExact(a, b);
            System.out.println(label + " = " + r);
        } catch (ArithmeticException e) {
            System.out.println(label + " overflow: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        p("abs(-5)", Math.abs(-5));
        p("abs(MIN_VALUE)", Math.abs(Integer.MIN_VALUE));
        p("abs(Long.MIN)", Math.abs(Long.MIN_VALUE));
        p("abs(-0.0)", Math.abs(-zero()));
        p("abs(NaN)", Math.abs(nan()));
        p("abs(-2.5f)", Math.abs(-2.5f));
        p("min/max int", Math.min(3, -4) + " " + Math.max(3, -4) + " " + Math.max(Integer.MIN_VALUE, -1));
        p("min/max long", Math.min(1L << 40, 5L) + " " + Math.max(-1L, Long.MIN_VALUE));
        p("max(0.0,-0.0)", Math.max(0.0, -zero()) + " " + Math.max(-zero(), 0.0));
        p("min(0.0,-0.0)", Math.min(0.0, -zero()) + " " + Math.min(-zero(), 0.0));
        p("max(NaN,1)", Math.max(nan(), 1.0) + " " + Math.max(1.0, nan()));
        p("min(NaN,1)", Math.min(nan(), 1.0) + " " + Math.min(1.0f, (float) nan()));
        p("round .5 cases", Math.round(0.5) + " " + Math.round(1.5) + " " + Math.round(2.5) + " " + Math.round(-0.5)
            + " " + Math.round(-1.5) + " " + Math.round(-2.5));
        p("round other", Math.round(2.4) + " " + Math.round(2.6) + " " + Math.round(-2.4) + " " + Math.round(-2.6)
            + " " + Math.round(0.49999999999999994));
        p("round float", Math.round(0.5f) + " " + Math.round(-0.5f) + " " + Math.round(2.5f) + " " + Math.round(-2.5f));
        p("round saturate", Math.round(1e30) + " " + Math.round(-1e30) + " " + Math.round(nan()) + " " + Math.round(3e10f));
        p("floor", Math.floor(2.7) + " " + Math.floor(-2.7) + " " + Math.floor(-0.0) + " " + Math.floor(5.0));
        p("ceil", Math.ceil(2.1) + " " + Math.ceil(-2.1) + " " + Math.ceil(-0.5) + " " + Math.ceil(5.0));
        p("rint", Math.rint(2.5) + " " + Math.rint(3.5) + " " + Math.rint(-2.5) + " " + Math.rint(2.6));
        p("floor/ceil special", Math.floor(nan()) + " " + Math.ceil(1.0 / zero()) + " " + Math.floor(-1.0 / zero()));
        p("floorDiv", Math.floorDiv(7, 2) + " " + Math.floorDiv(-7, 2) + " " + Math.floorDiv(7, -2) + " " + Math.floorDiv(-7, -2)
            + " " + Math.floorDiv(-8, 2));
        p("floorMod", Math.floorMod(7, 3) + " " + Math.floorMod(-7, 3) + " " + Math.floorMod(7, -3) + " " + Math.floorMod(-7, -3)
            + " " + Math.floorMod(-9, 3));
        p("floorDiv long", Math.floorDiv(-7L, 2L) + " " + Math.floorMod(-7L, 2L) + " " + Math.floorMod(-7L, 5));
        p("floorDiv MIN/-1", Math.floorDiv(Integer.MIN_VALUE, -1));
        try {
            Math.floorMod(5, 0);
        } catch (ArithmeticException e) {
            System.out.println("floorMod by zero: " + e.getMessage());
        }
        exact("addExact ok", 1000, 2000, '+');
        exact("addExact max+1", Integer.MAX_VALUE, 1, '+');
        exact("addExact min+-1", Integer.MIN_VALUE, -1, '+');
        exact("subtractExact min-1", Integer.MIN_VALUE, 1, '-');
        exact("subtractExact ok", 5, 9, '-');
        exact("multiplyExact big", 65536, 65536, '*');
        exact("multiplyExact ok", 46340, 46340, '*');
        try {
            Math.addExact(Long.MAX_VALUE, 1L);
        } catch (ArithmeticException e) {
            System.out.println("long addExact: " + e.getMessage());
        }
        try {
            Math.multiplyExact(1L << 62, 2L);
        } catch (ArithmeticException e) {
            System.out.println("long multiplyExact: " + e.getMessage());
        }
        try {
            Math.toIntExact(1L << 31);
        } catch (ArithmeticException e) {
            System.out.println("toIntExact: " + e.getMessage());
        }
        try {
            Math.negateExact(Integer.MIN_VALUE);
        } catch (ArithmeticException e) {
            System.out.println("negateExact: " + e.getMessage());
        }
        try {
            Math.incrementExact(Integer.MAX_VALUE);
        } catch (ArithmeticException e) {
            System.out.println("incrementExact: " + e.getMessage());
        }
        p("toIntExact ok", Math.toIntExact(-5L));
        p("signum", Math.signum(-3.5) + " " + Math.signum(0.0) + " " + Math.signum(8.0) + " " + Math.signum(-2f));
        p("copySign", Math.copySign(3.0, -0.0) + " " + Math.copySign(-3.0, 1.0));
        p("IEEEremainder", Math.IEEEremainder(5.0, 3.0) + " " + Math.IEEEremainder(7.0, 2.0));
        p("toRadians", StrictMath.toRadians(180.0) + " " + StrictMath.toDegrees(StrictMath.PI));

        p("sqrt", StrictMath.sqrt(16.0) + " " + StrictMath.sqrt(2.0) + " " + StrictMath.sqrt(0.0) + " " + StrictMath.sqrt(-1.0)
            + " " + StrictMath.sqrt(1.0 / zero()));
        p("cbrt", StrictMath.cbrt(27.0) + " " + StrictMath.cbrt(-8.0) + " " + StrictMath.cbrt(2.0));
        p("pow", StrictMath.pow(2.0, 10.0) + " " + StrictMath.pow(2.0, -2.0) + " " + StrictMath.pow(9.0, 0.5)
            + " " + StrictMath.pow(10.0, 15.0) + " " + StrictMath.pow(-8.0, 1.0 / 3.0) + " " + StrictMath.pow(0.0, 0.0));
        p("pow special", StrictMath.pow(2.0, 0.5) + " " + StrictMath.pow(1.5, 2.5) + " " + StrictMath.pow(nan(), 0.0));
        p("exp", StrictMath.exp(0.0) + " " + StrictMath.exp(1.0) + " " + StrictMath.exp(-1.0) + " " + StrictMath.exp(10.0));
        p("log", StrictMath.log(1.0) + " " + StrictMath.log(StrictMath.E) + " " + StrictMath.log(10.0) + " " + StrictMath.log(0.0)
            + " " + StrictMath.log(-1.0));
        p("log10", StrictMath.log10(1000.0) + " " + StrictMath.log10(2.0));
        p("sin", StrictMath.sin(0.0) + " " + StrictMath.sin(1.0) + " " + StrictMath.sin(StrictMath.PI) + " " + StrictMath.sin(StrictMath.PI / 2));
        p("cos", StrictMath.cos(0.0) + " " + StrictMath.cos(1.0) + " " + StrictMath.cos(StrictMath.PI) + " " + StrictMath.cos(StrictMath.PI / 2));
        p("tan", StrictMath.tan(0.0) + " " + StrictMath.tan(1.0) + " " + StrictMath.tan(StrictMath.PI / 4));
        p("atan", StrictMath.atan(1.0) + " " + StrictMath.atan(0.0) + " " + StrictMath.atan(1.0 / zero()));
        p("atan2", StrictMath.atan2(1.0, 1.0) + " " + StrictMath.atan2(1.0, -1.0) + " " + StrictMath.atan2(-1.0, -1.0)
            + " " + StrictMath.atan2(0.0, -1.0) + " " + StrictMath.atan2(-0.0, -1.0));
        p("asin/acos", StrictMath.asin(1.0) + " " + StrictMath.acos(1.0) + " " + StrictMath.acos(0.0) + " " + StrictMath.asin(2.0));
        p("sinh/cosh/tanh", StrictMath.sinh(1.0) + " " + StrictMath.cosh(1.0) + " " + StrictMath.tanh(1.0));
        p("hypot", StrictMath.hypot(3.0, 4.0) + " " + StrictMath.hypot(1.0, 1.0) + " " + StrictMath.hypot(5.0, 12.0));
        p("expm1/log1p", StrictMath.expm1(1.0) + " " + StrictMath.log1p(1.0) + " " + StrictMath.expm1(0.0));
        p("Math.sqrt", Math.sqrt(49.0) + " " + Math.sqrt(2.0));
        p("Math.pow int-ish", Math.pow(3.0, 4.0) + " " + Math.pow(2.0, 62.0));
        System.out.println("done");
    }
}
