package java.lang.ref;

/**
 * Where cleared references are delivered. The collector pushes onto
 * {@code head} directly (crates/vm/src/gc.rs), so the field names are part
 * of that contract. Until threads exist (S6) {@code remove} waits by running
 * the collector and sleeping, since nothing else can enqueue.
 */
public class ReferenceQueue<T> {
    Reference<?> head;
    int length;

    public ReferenceQueue() {
    }

    boolean push(Reference<?> r) {
        if (r.enqueued || r.queue != this) {
            return false;
        }
        r.enqueued = true;
        r.next = head;
        head = r;
        length++;
        return true;
    }

    @SuppressWarnings("unchecked")
    public Reference<? extends T> poll() {
        Reference<?> r = head;
        if (r == null) {
            return null;
        }
        head = r.next;
        r.next = null;
        r.enqueued = false;
        // A reference leaves its queue for good once removed (Javadoc of enqueue).
        r.queue = null;
        length--;
        return (Reference<? extends T>) r;
    }

    public Reference<? extends T> remove(long timeout) throws InterruptedException {
        if (timeout < 0) {
            throw new IllegalArgumentException("Negative timeout value");
        }
        long now = System.currentTimeMillis();
        // A timeout too large to add to the clock means no deadline.
        long deadline = timeout == 0 || timeout > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeout;
        while (true) {
            Reference<? extends T> r = poll();
            if (r != null || System.currentTimeMillis() >= deadline) {
                return r;
            }
            System.gc();
            r = poll();
            if (r != null) {
                return r;
            }
            Thread.sleep(Math.min(10, Math.max(1, deadline - System.currentTimeMillis())));
        }
    }

    public Reference<? extends T> remove() throws InterruptedException {
        return remove(0);
    }
}
