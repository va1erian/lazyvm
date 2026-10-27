package java.lang.ref;

/**
 * A reference cleared only when memory runs short: LazyVM clears soft
 * references in the last collection before an OutOfMemoryError.
 */
public class SoftReference<T> extends Reference<T> {
    public SoftReference(T referent) {
        super(referent);
    }

    public SoftReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }

    public T get() {
        return super.get();
    }
}
