package java.util;

/** The 48-bit linear congruential generator the Javadoc specifies, constants included. */
public class Random implements java.io.Serializable {
    private static final long TIMES = 0x5DEECE66DL;
    private static final long PLUS = 0xBL;
    private static final long LOW_48_BITS = (1L << 48) - 1;

    private long seed;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    public Random() { this(freshSeed()); }

    // Spec 10.X1: a subclass sees exactly one setSeed call; Random itself sets the seed directly.
    public Random(long seed) {
        if (getClass() == Random.class) {
            this.seed = firstSeed(seed);
        } else {
            setSeed(seed);
        }
    }

    /**
     * util.md R3: a process-relative clock would repeat across runs, so the seed comes from the
     * VM's entropy source, which also differs on every call (one seed per instance).
     */
    private static long freshSeed() { return lazy.internal.VM.entropy(); }

    private static long firstSeed(long seed) { return (seed ^ TIMES) & LOW_48_BITS; }

    public synchronized void setSeed(long seed) {
        this.seed = firstSeed(seed);
        haveNextNextGaussian = false;
    }

    protected synchronized int next(int bits) {
        seed = (seed * TIMES + PLUS) & LOW_48_BITS;
        return (int) (seed >>> (48 - bits));
    }

    public void nextBytes(byte[] bytes) {
        for (int i = 0; i < bytes.length;) {
            int rnd = nextInt();
            for (int n = Math.min(bytes.length - i, 4); n-- > 0; rnd >>= 8) {
                bytes[i++] = (byte) rnd;
            }
        }
    }

    public int nextInt() { return next(32); }

    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        if ((bound & -bound) == bound) {
            return (int) ((bound * (long) next(31)) >> 31);
        }
        int bits;
        int val;
        do {
            bits = next(31);
            val = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    public long nextLong() { return ((long) next(32) << 32) + next(32); }
    public boolean nextBoolean() { return next(1) != 0; }
    public float nextFloat() { return next(24) / ((float) (1 << 24)); }
    public double nextDouble() { return (((long) next(26) << 27) + next(27)) / (double) (1L << 53); }

    /** The polar method, as the Javadoc gives it. */
    public synchronized double nextGaussian() {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double v1;
        double v2;
        double s;
        do {
            v1 = 2 * nextDouble() - 1;
            v2 = 2 * nextDouble() - 1;
            s = v1 * v1 + v2 * v2;
        } while (s >= 1 || s == 0);
        double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s) / s);
        nextNextGaussian = v2 * multiplier;
        haveNextNextGaussian = true;
        return v1 * multiplier;
    }
}
