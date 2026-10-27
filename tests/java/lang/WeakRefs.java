// VMARGS: -Xmx64m
// TIMEOUT: 120
package lang;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;

/** Weak, soft and phantom references with the collector, and their queues. */
public class WeakRefs {
    static Object keep;

    public static void main(String[] args) throws Exception {
        ReferenceQueue<Object> q = new ReferenceQueue<Object>();
        keep = new Object();
        WeakReference<Object> kept = new WeakReference<Object>(keep, q);
        WeakReference<Object> dropped = new WeakReference<Object>(new Object(), q);
        System.out.println("before gc: kept " + (kept.get() != null) + ", queued " + (q.poll() != null));
        System.gc();
        System.out.println("after gc: kept " + (kept.get() != null) + ", dropped cleared " + (dropped.get() == null));
        Reference<?> r = q.remove(5000);
        System.out.println("queued the dropped one " + (r == dropped) + ", enqueued now " + dropped.isEnqueued());
        System.out.println("queue empty " + (q.poll() == null));
        System.out.println("enqueue after removal " + dropped.enqueue());

        WeakReference<Object> manual = new WeakReference<Object>(new Object(), q);
        System.out.println("manual enqueue " + manual.enqueue() + " again " + manual.enqueue());
        System.out.println("manual polled " + (q.poll() == manual));
        manual.clear();
        System.out.println("cleared " + (manual.get() == null));

        PhantomReference<Object> phantom = new PhantomReference<Object>(new Object(), q);
        System.out.println("phantom get null " + (phantom.get() == null));

        SoftReference<byte[]> soft = new SoftReference<byte[]>(new byte[4 << 20]);
        System.gc();
        System.out.println("soft survives gc " + (soft.get() != null));
        Object[] hog = new Object[1024];
        int i = 0;
        try {
            while (i < hog.length) {
                hog[i++] = new byte[1 << 20];
            }
            System.out.println("no OOM");
        } catch (OutOfMemoryError e) {
            hog = null;
            System.out.println("OOM after soft cleared " + (soft.get() == null));
        }
        System.out.println("done");
    }
}
