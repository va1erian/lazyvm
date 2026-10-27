package lazy.internal;

import java.util.Random;

/**
 * The digits in temporary file names (io.md section 16: free). Seeded from
 * the wall clock too, since nanoTime counts from the start of the process
 * and would repeat the same names in every run.
 */
public final class TempNames {
    private static final Random RANDOM = new Random(System.currentTimeMillis() ^ System.nanoTime());

    private TempNames() {
    }

    /** A fresh unsigned number for a name. */
    public static synchronized long next() {
        return RANDOM.nextLong() & Long.MAX_VALUE;
    }
}
