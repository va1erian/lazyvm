package lang;

import java.lang.ref.WeakReference;

/**
 * Weak references made while young objects are being collected all the
 * time, with nothing else allocated: a collection then often falls between
 * a reference's allocation and its constructor storing the referent, so the
 * reference is old before it holds a young referent. Whatever get() returns
 * later must be null or the very object it was given.
 */
public class WeakYoungReferents {
    static final int RING = 1 << 18;

    public static void main(String[] args) {
        @SuppressWarnings("unchecked")
        WeakReference<int[]>[] ring = new WeakReference[RING];
        int[][] kept = new int[RING][];
        int wrong = 0;
        int lost = 0;
        for (int i = 0; i < 3_000_000; i++) {
            int slot = i % RING;
            // The referent is made after the reference object, as the
            // constructor's argument: young when the reference may be old.
            ring[slot] = new WeakReference<>(new int[] {i});
            kept[slot] = i % 2 == 0 ? ring[slot].get() : null;
            if (slot == RING - 1) {
                for (int k = 0; k < RING; k++) {
                    int[] y = ring[k].get();
                    if (y != null && y[0] % RING != k) wrong++;
                    if (kept[k] != null && y != kept[k]) lost++;
                }
            }
        }
        System.out.println("wrong " + wrong + ", lost " + lost);
    }
}
