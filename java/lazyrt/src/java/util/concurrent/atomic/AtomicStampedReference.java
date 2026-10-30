package java.util.concurrent.atomic;

public class AtomicStampedReference<V> {
    /** One immutable value of the pair, so both parts change together. */
    private static final class Snapshot<V> {
        final V reference;
        final int stamp;

        Snapshot(V reference, int stamp) {
            this.reference = reference;
            this.stamp = stamp;
        }
    }

    private final AtomicReference<Snapshot<V>> state;

    public AtomicStampedReference(V initialRef, int initialStamp) {
        state = new AtomicReference<Snapshot<V>>(new Snapshot<V>(initialRef, initialStamp));
    }

    public V getReference() {
        return state.get().reference;
    }

    public int getStamp() {
        return state.get().stamp;
    }

    public V get(int[] stampHolder) {
        Snapshot<V> s = state.get();
        stampHolder[0] = s.stamp;
        return s.reference;
    }

    public boolean weakCompareAndSet(V expectedReference, V newReference, int expectedStamp, int newStamp) {
        return compareAndSet(expectedReference, newReference, expectedStamp, newStamp);
    }

    public boolean compareAndSet(V expectedReference, V newReference, int expectedStamp, int newStamp) {
        Snapshot<V> next = new Snapshot<V>(newReference, newStamp);
        for (;;) {
            Snapshot<V> s = state.get();
            if (s.reference != expectedReference || s.stamp != expectedStamp) {
                return false;
            }
            if (state.compareAndSet(s, next)) {
                return true;
            }
        }
    }

    public void set(V newReference, int newStamp) {
        state.set(new Snapshot<V>(newReference, newStamp));
    }

    public boolean attemptStamp(V expectedReference, int newStamp) {
        for (;;) {
            Snapshot<V> s = state.get();
            if (s.reference != expectedReference) {
                return false;
            }
            if (state.compareAndSet(s, new Snapshot<V>(s.reference, newStamp))) {
                return true;
            }
        }
    }
}
