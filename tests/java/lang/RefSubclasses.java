// VMARGS: -Xmx64m
package lang;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/** Reference and queue subclasses whose own fields reuse the library's names. */
public class RefSubclasses {
    static class MyRef extends WeakReference<Object> {
        ReferenceQueue<Object> queue;
        boolean enqueued = true;
        Reference<?> next;

        MyRef(Object o, ReferenceQueue<Object> q) {
            super(o, q);
        }
    }

    static class MyQueue extends ReferenceQueue<Object> {
        Reference<?> head;
        int length = 42;
    }

    public static void main(String[] args) throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<Object>();
        MyRef r = new MyRef(new Object(), q);
        System.gc();
        System.out.println("subclass ref queued " + (q.remove(2000) == r) + " own field " + r.enqueued);
        MyQueue mq = new MyQueue();
        WeakReference<Object> w = new WeakReference<Object>(new Object(), mq);
        System.gc();
        System.out.println("subclass queue " + (mq.remove(2000) == w) + " own fields " + mq.length + " " + (mq.head == null));
        System.out.println("done");
    }
}
