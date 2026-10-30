package java.util.concurrent;

import java.util.Random;

/**
 * One generator per thread, so no draw needs a lock. The generator is xorshift64* (Vigna, "An
 * experimental exploration of Marsaglia's xorshift generators, scrambled", 2014); nothing pins
 * the values. The inherited bounded and floating-point forms work through {@link #next}.
 */
public class ThreadLocalRandom extends Random {
    private static final ThreadLocal<ThreadLocalRandom> MINE = new ThreadLocal<ThreadLocalRandom>() {
        @Override
        protected ThreadLocalRandom initialValue() {
            return new ThreadLocalRandom();
        }
    };

    private long state;
    private final boolean built;

    private ThreadLocalRandom() {
        // Random's constructor calls setSeed on a subclass; built is still false then.
        super(0);
        long seed = lazy.internal.VM.entropy();
        state = seed != 0 ? seed : 1;
        built = true;
    }

    public static ThreadLocalRandom current() {
        return MINE.get();
    }

    public void setSeed(long seed) {
        if (built) {
            throw new UnsupportedOperationException();
        }
    }

    protected int next(int bits) {
        return (int) (nextLong() >>> (64 - bits));
    }

    public int nextInt() {
        return (int) (nextLong() >>> 32);
    }

    public int nextInt(int bound) {
        return super.nextInt(bound);
    }

    public int nextInt(int origin, int bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        return (int) (origin + below(bound - (long) origin));
    }

    public long nextLong() {
        long x = state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        state = x;
        return x * 1181783497276652981L;
    }

    public long nextLong(long bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return below(bound);
    }

    public long nextLong(long origin, long bound) {
        if (origin >= bound) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        long span = bound - origin;
        if (span > 0) {
            return origin + below(span);
        }
        // The span does not fit a long, so at least half of all values are in range: draw until one is.
        long v;
        do {
            v = nextLong();
        } while (v < origin || v >= bound);
        return v;
    }

    /** Uniform in [0, bound): the few values of a partial last cycle of the remainder are redrawn. */
    private long below(long bound) {
        long bits;
        long v;
        do {
            bits = nextLong() >>> 1;
            v = bits % bound;
        } while (bits - v + (bound - 1) < 0);
        return v;
    }

    public double nextDouble() {
        return super.nextDouble();
    }

    public double nextDouble(double bound) {
        if (!(bound > 0)) {
            throw new IllegalArgumentException("bound must be positive");
        }
        return clamp(nextDouble() * bound, bound);
    }

    public double nextDouble(double origin, double bound) {
        if (!(origin < bound)) {
            throw new IllegalArgumentException("bound must be greater than origin");
        }
        double r = nextDouble();
        double span = bound - origin;
        // A span too wide for a double would make every draw infinite.
        double v = Double.isInfinite(span) ? r * bound + (1 - r) * origin : origin + r * span;
        return clamp(v, bound);
    }

    /** Rounding can land on the excluded bound: the next double down is in range. */
    private static double clamp(double v, double bound) {
        if (v < bound) {
            return v;
        }
        long bits = Double.doubleToLongBits(bound);
        return bound > 0 ? Double.longBitsToDouble(bits - 1)
            : bound == 0 ? -Double.MIN_VALUE : Double.longBitsToDouble(bits + 1);
    }

    public boolean nextBoolean() {
        return super.nextBoolean();
    }

    public float nextFloat() {
        return super.nextFloat();
    }

    public double nextGaussian() {
        return super.nextGaussian();
    }
}
