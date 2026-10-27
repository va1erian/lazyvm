package java.lang.ref;

/**
 * The base of reference objects. The collector treats {@code referent} as not
 * keeping its object alive; when it clears the referent it also puts the
 * reference on its queue (crates/vm/src/gc.rs). The field names are part of
 * that contract.
 */
public abstract class Reference<T> {
    private T referent;
    ReferenceQueue<? super T> queue;
    Reference<?> next;
    boolean enqueued;

    Reference(T referent) {
        this(referent, null);
    }

    Reference(T referent, ReferenceQueue<? super T> queue) {
        this.referent = referent;
        this.queue = queue;
    }

    /** The referent, or null once it has been cleared. */
    public T get() {
        return referent;
    }

    public void clear() {
        referent = null;
    }

    /** Whether this reference is on its queue now. */
    public boolean isEnqueued() {
        return enqueued;
    }

    /**
     * Puts this reference on its queue; false without a queue or if already
     * queued. Java 8 also clears the referent here (observed on Temurin 8).
     */
    public boolean enqueue() {
        ReferenceQueue<? super T> q = queue;
        if (q == null || !q.push(this)) {
            return false;
        }
        referent = null;
        return true;
    }
}
