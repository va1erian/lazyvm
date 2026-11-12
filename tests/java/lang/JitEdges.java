package lang;

/**
 * Edge cases of the instructions LazyVM's JIT compiles inline (run with
 * LAZYVM_JIT=all so every method here is compiled): integer division by -1
 * and MIN, shift counts past the width, float to integer conversion of NaN,
 * infinities and out-of-range values, compares with NaN, narrowing, the
 * stack shuffles, wide locals, statics, every array kind, and loops.
 */
public class JitEdges {
    static int si = 7;
    static long sl = -3L;
    static double sd = 2.5;
    static String ss = "s";

    // Floats and doubles print as bits: this tests arithmetic, not toString.
    static String fb(float f) { return Integer.toHexString(Float.floatToRawIntBits(f)); }
    static String db(double d) { return Long.toHexString(Double.doubleToRawLongBits(d)); }

    static int idiv(int a, int b) { return a / b; }
    static int irem(int a, int b) { return a % b; }
    static long ldiv(long a, long b) { return a / b; }
    static long lrem(long a, long b) { return a % b; }

    static void ints() {
        int[] xs = {0, 1, -1, 7, -7, Integer.MIN_VALUE, Integer.MAX_VALUE};
        StringBuilder sb = new StringBuilder("ints:");
        for (int a : xs) {
            for (int b : xs) {
                sb.append(' ').append(a + b).append(',').append(a - b).append(',').append(a * b);
                if (b != 0) {
                    sb.append(',').append(idiv(a, b)).append(',').append(irem(a, b));
                }
                sb.append(',').append(a << b).append(',').append(a >> b).append(',').append(a >>> b);
                sb.append(',').append(a & b).append(',').append(a | b).append(',').append(a ^ b);
            }
            sb.append(" n").append(-a).append(" b").append((byte) a).append(" c").append((int) (char) a)
                .append(" s").append((short) a);
        }
        System.out.println(sb);
        try {
            System.out.println(idiv(1, 0));
        } catch (ArithmeticException e) {
            System.out.println("idiv0 " + e.getMessage());
        }
    }

    static void longs() {
        long[] xs = {0, 1, -1, 7, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 40};
        StringBuilder sb = new StringBuilder("longs:");
        for (long a : xs) {
            for (long b : xs) {
                sb.append(' ').append(a + b).append(',').append(a - b).append(',').append(a * b);
                if (b != 0) {
                    sb.append(',').append(ldiv(a, b)).append(',').append(lrem(a, b));
                }
                int sh = (int) b;
                sb.append(',').append(a << sh).append(',').append(a >> sh).append(',').append(a >>> sh);
                sb.append(',').append(a & b).append(',').append(a | b).append(',').append(a ^ b);
                sb.append(',').append(Long.compare(a, b)).append(a < b ? 'l' : a == b ? 'e' : 'g');
            }
            sb.append(" n").append(-a).append(" i").append((int) a).append(" f").append(fb((float) a))
                .append(" d").append(db((double) a));
        }
        System.out.println(sb);
        try {
            System.out.println(lrem(1, 0));
        } catch (ArithmeticException e) {
            System.out.println("lrem0 " + e.getMessage());
        }
    }

    static void floats() {
        float[] fs = {0f, -0f, 1.5f, -2.75f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            3e9f, -3e9f, 1e20f, Float.MIN_VALUE, Float.MAX_VALUE};
        double[] ds = {0d, -0d, 1.5, -2.75, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            3e9, -3e9, 1e20, 1e300, Double.MIN_VALUE};
        StringBuilder sb = new StringBuilder("floats:");
        for (float a : fs) {
            sb.append(' ').append((int) a).append(',').append((long) a).append(',').append(db((double) a))
                .append(',').append(fb(-a));
            for (float b : fs) {
                sb.append(';').append(fb(a + b)).append(',').append(fb(a * b)).append(',').append(fb(a / b))
                    .append(',').append(fb(a - b)).append(a < b ? 'l' : a == b ? 'e' : a > b ? 'g' : 'u')
                    .append(Float.compare(a, b));
            }
        }
        for (double a : ds) {
            sb.append(' ').append((int) a).append(',').append((long) a).append(',').append(fb((float) a))
                .append(',').append(db(-a));
            for (double b : ds) {
                sb.append(';').append(db(a + b)).append(',').append(db(a * b)).append(',').append(db(a / b))
                    .append(',').append(db(a - b)).append(a < b ? 'l' : a == b ? 'e' : a > b ? 'g' : 'u');
            }
        }
        int i = 123456789;
        long l = -987654321012345L;
        sb.append(" conv ").append(fb((float) i)).append(',').append(db((double) i)).append(',')
            .append(fb((float) l)).append(',').append(db((double) l));
        System.out.println(sb);
    }

    static long dup2x2(long a, long b) {
        long[] arr = {0};
        arr[0] = a;
        long c = arr[0] = b; // dup2_x2 forms appear in such chains
        return a + b + c;
    }

    static int shuffles(int a, int b) {
        int[] arr = new int[2];
        int x = arr[0] = a; // dup_x2
        int y = arr[1] = b;
        long w = 5L;
        long z = w++; // dup2
        return x * 31 + y + arr[0] + arr[1] + (int) z + (int) w;
    }

    static double wideLocals(int n) {
        double d = 0.5;
        long l = 1;
        int i = 3;
        for (int k = 0; k < n; k++) {
            d = d * 1.5 + i;
            l = l * 3 + k;
            i = (int) l ^ i;
        }
        return d + l + i;
    }

    static String statics() {
        si += 5;
        sl *= si;
        sd /= 2;
        ss = ss + si;
        return si + "," + sl + "," + db(sd) + "," + ss;
    }

    static String arrays() {
        boolean[] z = {true, false};
        byte[] b = {(byte) 200, 5};
        char[] c = {'a', (char) 60000};
        short[] s = {(short) 40000, -1};
        int[] i = {1, 2};
        long[] l = {1L << 50, -1};
        float[] f = {1.5f, Float.NaN};
        double[] d = {2.5, -0d};
        Object[] o = {"x", null};
        b[1] = (byte) (b[0] + 1);
        c[1] = (char) (c[0] + c[1]);
        s[1] = (short) (s[0] * 2);
        i[1] = i[0] + i.length;
        l[1] = l[0] >> 3;
        f[1] = f[0] * 2;
        d[1] = d[0] - 1;
        z[1] = !z[0];
        o[1] = o[0];
        try {
            i[2] = 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            o[0] = "oob " + e.getMessage();
        }
        return z[1] + "," + b[1] + "," + (int) c[1] + "," + s[1] + "," + i[1] + "," + l[1] + "," + fb(f[1]) + ","
            + db(d[1]) + "," + o[0] + "," + o[1];
    }

    static int loops() {
        int sum = 0;
        for (int i = 0; i < 300; i++) {
            for (int j = i; j > 0; j -= 7) {
                sum += (j & 1) == 0 ? j : -j;
                if (sum > 100000) {
                    sum -= 99999;
                }
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        ints();
        longs();
        floats();
        System.out.println("dup2x2 " + dup2x2(3, 4) + " shuffles " + shuffles(5, 6));
        System.out.println("wide " + db(wideLocals(40)));
        System.out.println("statics " + statics() + " " + statics());
        System.out.println("arrays " + arrays());
        System.out.println("loops " + loops());
        Object n = null;
        try {
            System.out.println(n.hashCode());
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
    }
}
