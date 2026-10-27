package java.lang.ref;

/** A reference whose referent can never be retrieved; it only signals collection. */
public class PhantomReference<T> extends Reference<T> {
    public PhantomReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }

    /** Always null, so the referent cannot be revived. */
    public T get() {
        return null;
    }
}
