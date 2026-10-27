package util;

import java.util.*;

/** Random: sequences from seeds, the next(bits) calls each method makes, and new Random() seeding (docs/design/util.md section 4). */
public class RandomSeq {
    static final long MULT = 0x5DEECE66DL;
    static final long ADD = 0xBL;
    static final long MASK = (1L << 48) - 1;

    /** Logs every next(bits) call. */
    static final class Logged extends Random {
        final StringBuilder log = new StringBuilder();
        Logged(long seed) { super(seed); }
        protected int next(int bits) {
            log.append(bits).append(',');
            return super.next(bits);
        }
        String take() {
            String s = log.toString();
            log.setLength(0);
            return s;
        }
    }

    /** next(bits) returns a fixed script, to drive the callers through chosen values. */
    static final class Scripted extends Random {
        final int[] script;
        int at;
        Scripted(int... script) { super(0); this.script = script; }
        protected int next(int bits) { return script[at++ % script.length] >>> (32 - bits); }
    }

    public static void main(String[] args) {
        calls();
        sequences();
        bounds();
        bytes();
        scripted();
        gaussians();
        seeding();
        System.out.println("done");
    }

    static void calls() {
        // R2: the next(bits) calls each method makes.
        Logged r = new Logged(1);
        r.nextInt(); System.out.println("nextInt: " + r.take());
        r.nextLong(); System.out.println("nextLong: " + r.take());
        r.nextBoolean(); System.out.println("nextBoolean: " + r.take());
        r.nextFloat(); System.out.println("nextFloat: " + r.take());
        r.nextDouble(); System.out.println("nextDouble: " + r.take());
        r.nextInt(16); System.out.println("nextInt(16): " + r.take());
        r.nextInt(1); System.out.println("nextInt(1): " + r.take());
        r.nextBytes(new byte[0]); System.out.println("nextBytes(0): " + r.take());
        r.nextBytes(new byte[5]); System.out.println("nextBytes(5): " + r.take());
        r.nextBytes(new byte[8]); System.out.println("nextBytes(8): " + r.take());
        r.setSeed(3);
        r.nextGaussian(); System.out.println("nextGaussian: " + r.take());
        r.nextGaussian(); System.out.println("nextGaussian cached: " + r.take());
        r.nextGaussian(); System.out.println("nextGaussian again: " + r.take());
        // R2: rejection in nextInt(bound): count next(31) calls over many draws with a bound near 2^30.
        StringBuilder counts = new StringBuilder();
        int bound = (1 << 30) + 1;
        for (int i = 0; i < 12; i++) {
            r.nextInt(bound);
            counts.append(r.take().length() / 3).append(' ');
        }
        System.out.println("nextInt(2^30+1) draws: " + counts.toString().trim());
        try { r.nextInt(0); } catch (IllegalArgumentException e) { System.out.println("nextInt(0): " + e.getMessage() + " calls [" + r.take() + "]"); }
    }

    static void sequences() {
        // R1: the sequence of every method from many seeds, folded into one checksum per method.
        long g = 88172645463325252L;
        long ints = 0, longs = 0, bools = 0, floats = 0, doubles = 0;
        for (int i = 0; i < 300; i++) {
            g ^= g << 13; g ^= g >>> 7; g ^= g << 17;
            long seed = (i < 6) ? new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, MULT}[i] : g;
            Random r = new Random(seed);
            for (int k = 0; k < 7; k++) {
                ints = ints * 31 + r.nextInt();
                longs = longs * 31 + r.nextLong();
                bools = bools * 3 + (r.nextBoolean() ? 1 : 2);
                floats = floats * 31 + Float.floatToIntBits(r.nextFloat());
                doubles = doubles * 31 + Double.doubleToLongBits(r.nextDouble());
            }
        }
        System.out.println("sums: " + ints + " " + longs + " " + bools + " " + floats + " " + doubles);
        Random r = new Random(-77);
        System.out.println("seed -77: " + r.nextInt() + " " + r.nextLong() + " " + r.nextFloat() + " " + r.nextDouble() + " " + r.nextBoolean());
        // R1: setSeed restarts the sequence and drops a cached gaussian.
        r.setSeed(5);
        double g1 = r.nextGaussian();
        r.setSeed(5);
        System.out.println("setSeed restarts: " + (g1 == r.nextGaussian()) + " " + (new Random(5).nextInt() == new Random(5).nextInt()));
    }

    static void bounds() {
        // R1: nextInt(bound) for powers of two, small bounds and bounds near the top.
        int[] bs = {1, 2, 3, 5, 7, 10, 16, 100, 1000, 1 << 20, (1 << 30) + 1, Integer.MAX_VALUE, 1 << 30, 3 << 29};
        StringBuilder sb = new StringBuilder();
        for (int b : bs) {
            Random r = new Random(b);
            long sum = 0;
            for (int i = 0; i < 50; i++) sum = sum * 31 + r.nextInt(b);
            sb.append(b).append('=').append(sum).append(' ');
        }
        System.out.println("bounded: " + sb.toString().trim());
        Random r = new Random(9);
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < 20; i++) first.append(r.nextInt(6)).append(' ');
        System.out.println("dice: " + first.toString().trim());
        try { r.nextInt(Integer.MIN_VALUE); } catch (IllegalArgumentException e) { System.out.println("MIN_VALUE: " + e.getMessage()); }
    }

    static void bytes() {
        // R1: nextBytes fills from the low byte of each int; a partial last int is cut short.
        for (int n = 0; n <= 9; n++) {
            byte[] b = new byte[n];
            new Random(n).nextBytes(b);
            System.out.println("bytes " + n + ": " + Arrays.toString(b));
        }
        Random r = new Random(4);
        byte[] b = new byte[3];
        r.nextBytes(b);
        System.out.println("after 3 bytes next int: " + r.nextInt() + " vs " + skipOne(4));
        try { r.nextBytes(null); } catch (NullPointerException e) { System.out.println("nextBytes(null): NPE"); }
    }

    static int skipOne(long seed) {
        Random r = new Random(seed);
        r.nextInt();
        return r.nextInt();
    }

    static void scripted() {
        // R1: the callers turn next(bits) results into values by the Javadoc's formulas, edge values included.
        Scripted s = new Scripted(-1, -1, 0, 0, 0x80000000, 1);
        System.out.println("scripted: " + s.nextInt() + " " + s.nextLong() + " " + s.nextFloat() + " " + s.nextDouble()
                + " " + s.nextBoolean() + " " + s.nextBoolean());
        Scripted top = new Scripted(-1);
        System.out.println("scripted top: " + top.nextFloat() + " " + top.nextDouble() + " " + top.nextInt(16) + " " + top.nextInt(1 << 30));
        // R1: a rejected draw (bits - val + (bound - 1) overflows) is followed by a fresh one.
        Scripted rej = new Scripted(-1, 0x40000000);
        System.out.println("rejected: " + rej.nextInt((1 << 30) + 1) + " after " + rej.at);
        Scripted bytes = new Scripted(0x04030201, 0x08070605);
        byte[] b = new byte[6];
        bytes.nextBytes(b);
        System.out.println("scripted bytes: " + Arrays.toString(b));
    }

    /**
     * A gaussian to 10 significant digits. LazyVM's StrictMath.log may be a few ulps from fdlibm's
     * (docs/decisions.md D12), which never reaches the 10th digit of these values; the draws
     * (the LCG, the rejections and the pairing) stay exact.
     */
    static String g(double v) { return String.format(Locale.ROOT, "%.9e", v); }

    static void gaussians() {
        // R1: the polar method with StrictMath, two values per round.
        Random r = new Random(2024);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(g(r.nextGaussian())).append(' ');
        System.out.println("gaussians: " + sb.toString().trim());
        // R1: 2000 more: the signs are exact (log only scales), the sums are rounded far above an ulp.
        int negative = 0;
        double sum = 0;
        double squares = 0;
        for (int i = 0; i < 2000; i++) {
            double v = r.nextGaussian();
            if (v < 0) negative++;
            sum += v;
            squares += v * v;
        }
        System.out.println("gaussian sum: 2000 negative " + negative + " sum " + String.format(Locale.ROOT, "%.6f", sum)
            + " squares " + String.format(Locale.ROOT, "%.6f", squares));
        // R1: a rejected pair (s >= 1 or s == 0) draws again; drive it with a script.
        Scripted s = new Scripted(-1, -1, -1, -1, 0x40000000, 0, 0x40000000, 0, 0x60000000, 0, 0x60000000, 0);
        System.out.println("scripted gaussian: " + g(s.nextGaussian()) + " " + g(s.nextGaussian()) + " after " + s.at);
    }

    static void seeding() {
        // R3: new Random() seeds differ between instances and are not a clock reading.
        Random a = new Random();
        Random b = new Random();
        System.out.println("two instances differ: " + (a.nextLong() != b.nextLong()));
        boolean clockFree = true;
        for (int i = 0; i < 8; i++) {
            long n0 = System.nanoTime(), m0 = System.currentTimeMillis();
            Random r = new Random();
            long n1 = System.nanoTime(), m1 = System.currentTimeMillis();
            long seed = recoverSeed(r.nextInt(), r.nextInt(), r.nextInt());
            if (seed < 0 || looksLikeClock(seed, n0, n1) || looksLikeClock(seed, m0, m1)) clockFree = false;
        }
        System.out.println("seeds are not clock readings: " + clockFree);
    }

    /** The low 48 bits of the seed given to Random(long) that yields these three nextInt values, or -1. */
    static long recoverSeed(int o1, int o2, int o3) {
        long found = -1;
        for (long low = 0; low < (1 << 16); low++) {
            long s1 = ((o1 & 0xFFFFFFFFL) << 16) | low;
            long s2 = (s1 * MULT + ADD) & MASK;
            if ((int) (s2 >>> 16) != o2) continue;
            long s3 = (s2 * MULT + ADD) & MASK;
            if ((int) (s3 >>> 16) != o3) continue;
            if (found >= 0) return -1;
            found = s1;
        }
        if (found < 0) return -1;
        long s0 = ((found - ADD) * inverse(MULT)) & MASK;
        return (s0 ^ MULT) & MASK;
    }

    static long inverse(long m) {
        long x = m;
        for (int i = 0; i < 6; i++) x *= 2 - m * x;
        return x;
    }

    /** Whether the seed, possibly mixed with a multiple of MULT per instance, falls in a clock window. */
    static boolean looksLikeClock(long seed, long from, long to) {
        for (long k = 0; k < 4096; k++) {
            long v = (seed ^ (k * MULT)) & MASK;
            if (v >= (from & MASK) && v <= (to & MASK)) return true;
        }
        return false;
    }
}
