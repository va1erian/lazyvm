package java.lang.ref;

/** A reference cleared as soon as its referent is only weakly reachable. */
public class WeakReference<T> extends Reference<T> {
    public WeakReference(T referent) {
        super(referent);
    }

    public WeakReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }
}
