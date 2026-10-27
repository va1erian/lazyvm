package lazy.internal;

/**
 * The order in which a java.util.HashMap of string keys iterates, for annotation toString: the
 * Java 8 output lists members with defaults in the order of the hash table that held them.
 */
final class HashOrder {
    private HashOrder() {
    }

    /** The table size a map built by n puts into `new HashMap(0)` ends with. */
    static int capacity(int n) {
        int cap = 1;
        int threshold = 0;
        for (int size = 1; size <= n; size++) {
            if (size > threshold) {
                int old = cap;
                cap <<= 1;
                threshold = old >= 16 ? threshold << 1 : (int) (cap * 0.75f);
            }
        }
        return cap;
    }

    /** Indexes into `keys` (inserted in that order) in iteration order: by bucket, then insertion. */
    static int[] order(String[] keys) {
        int n = keys.length;
        int mask = capacity(n) - 1;
        int[] bucket = new int[n];
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int h = keys[i].hashCode();
            bucket[i] = (h ^ (h >>> 16)) & mask;
            int j = i;
            while (j > 0 && bucket[out[j - 1]] > bucket[i]) {
                out[j] = out[j - 1];
                j--;
            }
            out[j] = i;
        }
        return out;
    }
}
