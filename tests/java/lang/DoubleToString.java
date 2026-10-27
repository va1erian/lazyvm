// ORACLE: modern
package lang;

/**
 * Double.toString and Float.toString on 100,000 pseudo-random bit patterns
 * each, plus edge values. Blessed on a modern JDK (docs/decisions.md D9):
 * the shortest form the Javadoc asks for, which Java 8 does not always print.
 */
public class DoubleToString {
    private static long state = 0x9E3779B97F4A7C15L;

    /** xorshift64*, so the test needs no java.util.Random. */
    static long next() {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return state * 0x2545F4914F6CDD1DL;
    }

    public static void main(String[] args) {
        double[] edges = {0.0, -0.0, Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
            1e-3, 1e-4, 1e7, 1e8, 1e23, 2e-44, 0.1 + 0.2, 512.0 + 14909.0 / 16384.0,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double d : edges) {
            System.out.println(Double.toString(d) + " " + Float.toString((float) d));
        }
        long hd = 0;
        long hf = 0;
        for (int i = 0; i < 100000; i++) {
            long bits = next();
            double d = Double.longBitsToDouble(bits);
            float f = Float.intBitsToFloat((int) bits);
            String sd = Double.toString(d);
            String sf = Float.toString(f);
            hd = hd * 31 + sd.hashCode();
            hf = hf * 31 + sf.hashCode();
            if (i % 5000 == 0) {
                System.out.println(sd + " " + sf + " " + Double.toString(f));
            }
        }
        System.out.println("double hash " + hd);
        System.out.println("float hash " + hf);
        System.out.println("done");
    }
}
