package java.util.concurrent.atomic;

public class AtomicMarkableReference<V> {
    /** A stamped reference whose stamp is 0 or 1. */
    private final AtomicStampedReference<V> pair;

    private static int stamp(boolean mark) {
        return mark ? 1 : 0;
    }

    public AtomicMarkableReference(V initialRef, boolean initialMark) {
        pair = new AtomicStampedReference<V>(initialRef, stamp(initialMark));
    }

    public V getReference() {
        return pair.getReference();
    }

    public boolean isMarked() {
        return pair.getStamp() != 0;
    }

    public V get(boolean[] markHolder) {
        int[] stampHolder = new int[1];
        V reference = pair.get(stampHolder);
        markHolder[0] = stampHolder[0] != 0;
        return reference;
    }

    public boolean weakCompareAndSet(V expectedReference, V newReference, boolean expectedMark, boolean newMark) {
        return compareAndSet(expectedReference, newReference, expectedMark, newMark);
    }

    public boolean compareAndSet(V expectedReference, V newReference, boolean expectedMark, boolean newMark) {
        return pair.compareAndSet(expectedReference, newReference, stamp(expectedMark), stamp(newMark));
    }

    public void set(V newReference, boolean newMark) {
        pair.set(newReference, stamp(newMark));
    }

    public boolean attemptMark(V expectedReference, boolean newMark) {
        return pair.attemptStamp(expectedReference, stamp(newMark));
    }
}
