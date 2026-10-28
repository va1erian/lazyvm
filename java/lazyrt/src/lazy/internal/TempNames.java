package lazy.internal;

import java.util.Random;

/** The digits in temporary file names (io.md section 16: free), from a seed that differs per run (util.md R3). */
public final class TempNames {
    private static final Random RANDOM = new Random();

    private TempNames() {
    }

    /** A fresh unsigned number for a name. */
    public static synchronized long next() {
        return RANDOM.nextLong() & Long.MAX_VALUE;
    }
}
