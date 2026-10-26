package java.lang;

public final class StrictMath {
    public static final double E = 2.7182818284590452354;
    public static final double PI = 3.14159265358979323846;

    private StrictMath() {
    }

    public static native double sin(double a);

    public static native double cos(double a);

    public static native double tan(double a);

    public static native double asin(double a);

    public static native double acos(double a);

    public static native double atan(double a);

    public static native double exp(double a);

    public static native double log(double a);

    public static native double log10(double a);

    public static native double sqrt(double a);

    public static native double cbrt(double a);

    public static native double ceil(double a);

    public static native double floor(double a);

    public static native double rint(double a);

    public static native double sinh(double x);

    public static native double cosh(double x);

    public static native double tanh(double x);

    public static native double expm1(double x);

    public static native double log1p(double x);

    public static native double atan2(double y, double x);

    public static native double pow(double a, double b);

    public static native double hypot(double x, double y);

    public static native double IEEEremainder(double f1, double f2);

    // The remaining methods are exact in every implementation, so they live in Math.

    public static int abs(int a) {
        return Math.abs(a);
    }

    public static long abs(long a) {
        return Math.abs(a);
    }

    public static float abs(float a) {
        return Math.abs(a);
    }

    public static double abs(double a) {
        return Math.abs(a);
    }

    public static int max(int a, int b) {
        return Math.max(a, b);
    }

    public static long max(long a, long b) {
        return Math.max(a, b);
    }

    public static float max(float a, float b) {
        return Math.max(a, b);
    }

    public static double max(double a, double b) {
        return Math.max(a, b);
    }

    public static int min(int a, int b) {
        return Math.min(a, b);
    }

    public static long min(long a, long b) {
        return Math.min(a, b);
    }

    public static float min(float a, float b) {
        return Math.min(a, b);
    }

    public static double min(double a, double b) {
        return Math.min(a, b);
    }

    public static int round(float a) {
        return Math.round(a);
    }

    public static long round(double a) {
        return Math.round(a);
    }

    public static double signum(double d) {
        return Math.signum(d);
    }

    public static float signum(float f) {
        return Math.signum(f);
    }

    public static double copySign(double magnitude, double sign) {
        return Math.copySign(magnitude, sign);
    }

    public static float copySign(float magnitude, float sign) {
        return Math.copySign(magnitude, sign);
    }

    public static double toRadians(double angdeg) {
        return Math.toRadians(angdeg);
    }

    public static double toDegrees(double angrad) {
        return Math.toDegrees(angrad);
    }
}
