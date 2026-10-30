package java.util.concurrent.locks;

import java.lang.reflect.Field;
import lazy.internal.Atomics;

/** The permit lives in the VM's thread table (docs/design/threads.md); the blocker in the Thread. */
public class LockSupport {
    // A private field of another package's class, so through the same door as the atomics.
    private static final Field BLOCKER = Atomics.field(Thread.class, "blockedOn");

    private LockSupport() {
    }

    public static void unpark(Thread thread) {
        if (thread != null) {
            unpark0(thread);
        }
    }

    public static void park(Object blocker) {
        parkFor(blocker, 0);
    }

    public static void parkNanos(Object blocker, long nanos) {
        if (nanos > 0) {
            parkFor(blocker, nanos);
        }
    }

    public static void parkUntil(Object blocker, long deadline) {
        long now = System.currentTimeMillis();
        if (deadline > now) {
            long millis = deadline - now;
            boolean fits = millis > 0 && millis <= Long.MAX_VALUE / 1000000L;
            parkFor(blocker, fits ? millis * 1000000L : Long.MAX_VALUE);
        }
    }

    private static void parkFor(Object blocker, long nanos) {
        Thread me = Thread.currentThread();
        Atomics.getAndSetRef(me, BLOCKER, blocker);
        park0(nanos);
        Atomics.getAndSetRef(me, BLOCKER, null);
    }

    public static Object getBlocker(Thread t) {
        if (t == null) {
            throw new NullPointerException();
        }
        return Atomics.getRef(t, BLOCKER);
    }

    public static void park() {
        park0(0);
    }

    public static void parkNanos(long nanos) {
        if (nanos > 0) {
            park0(nanos);
        }
    }

    public static void parkUntil(long deadline) {
        parkUntil(null, deadline);
    }

    /** Returns on the permit, the interrupt flag, or after `nanos`; 0 means no time limit. */
    private static native void park0(long nanos);

    private static native void unpark0(Thread thread);
}
