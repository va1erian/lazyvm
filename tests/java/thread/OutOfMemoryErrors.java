// VMARGS: -Xmx16m
package thread;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * What the VM's own errors look like, and that a program cannot use them to grow
 * the heap past its limit: it catches and keeps every error it gets, with the
 * heap full, and that must end in running out, whatever the VM makes them from.
 */
public class OutOfMemoryErrors {
    static final int HUGE = 1 << 27;

    static int depth;

    static void recurse() {
        depth++;
        recurse();
    }

    static void deep(int frames, Runnable r) {
        if (frames > 0) {
            deep(frames - 1, r);
        } else {
            r.run();
        }
    }

    static void heapSpace() {
        long[] a = new long[HUGE];
        depth = a.length;
    }

    static void arrayLimit() {
        long[] a = new long[Integer.MAX_VALUE];
        depth = a.length;
    }

    static final List<Throwable> seen = new ArrayList<>(64);

    static String describe(Throwable e) {
        boolean again = seen.contains(e);
        seen.add(e);
        String cause;
        try {
            e.initCause(new Error("c"));
            cause = "set";
        } catch (IllegalStateException x) {
            cause = x.getMessage();
        }
        e.addSuppressed(new Error("s"));
        StackTraceElement[] one = {new StackTraceElement("a", "b", "c", 1)};
        int before = e.getStackTrace().length;
        e.setStackTrace(one);
        int set = e.getStackTrace().length;
        e.fillInStackTrace();
        int filled = e.getStackTrace().length;
        return e.getClass().getName() + " again=" + again + " message=" + e.getMessage()
                + " cause: " + cause + " suppressed=" + e.getSuppressed().length
                + " trace=" + before + " set=" + set + " filled>0=" + (filled > 0);
    }

    static void catching(Runnable r) {
        try {
            deep(100, r);
            System.out.println("nothing thrown");
        } catch (Throwable e) {
            System.out.println(describe(e));
        }
    }

    /** The first two are new, whatever the message; then one object per message. */
    static void errorsOfTheVm() {
        catching(OutOfMemoryErrors::arrayLimit);
        catching(OutOfMemoryErrors::heapSpace);
        catching(OutOfMemoryErrors::arrayLimit);
        catching(OutOfMemoryErrors::heapSpace);
        catching(OutOfMemoryErrors::arrayLimit);
        catching(OutOfMemoryErrors::heapSpace);
        catching(OutOfMemoryErrors::recurse);
        catching(OutOfMemoryErrors::recurse);
        Throwable own = new OutOfMemoryError("own");
        own.initCause(new Error("c"));
        own.addSuppressed(new Error("s"));
        System.out.println("own: cause " + own.getCause() + " suppressed "
                + own.getSuppressed().length);
    }

    static Object[] kept = new Object[1 << 18];

    static List<Object> full;

    static void fill() {
        full = new ArrayList<>(1 << 16);
        int size = 1 << 20;
        while (size >= 64) {
            try {
                full.add(new byte[size]);
            } catch (OutOfMemoryError e) {
                size /= 2;
            }
        }
    }

    /** Keeps the exception each step throws, until the heap has no room for one. */
    static void keepAll(String what, int steps, Runnable step) {
        fill();
        boolean ranOut = false;
        boolean other = false;
        for (int i = 0; i < steps && !ranOut && !other; i++) {
            try {
                step.run();
                other = true;
            } catch (OutOfMemoryError e) {
                ranOut = true;
            } catch (Throwable e) {
                kept[i] = e;
            }
        }
        for (int i = 0; i < kept.length; i++) {
            kept[i] = null;
        }
        full = null;
        System.out.println(what + ": ran out " + ranOut + ", other " + other);
    }

    static Object nothing;

    static void retained() {
        keepAll("NullPointerException", kept.length, () -> nothing.hashCode());
        keepAll("ArithmeticException", kept.length, () -> depth = 1 / (depth - depth));
        keepAll("StackOverflowError", 2000, OutOfMemoryErrors::recurse);
        kept = null;
    }

    /** Named by a system property, so the library's initializer makes it. */
    public static class Factory implements ForkJoinPool.ForkJoinWorkerThreadFactory {
        static long inInitializer = allocateAll();
        long inConstructor = allocateAll();

        static long fromConstructor;

        public Factory() {
            fromConstructor = inConstructor;
        }

        static long allocateAll() {
            List<byte[]> list = new ArrayList<>();
            try {
                while (true) {
                    list.add(new byte[1 << 16]);
                }
            } catch (OutOfMemoryError e) {
                return (long) list.size() << 16;
            }
        }

        @Override
        public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
            return ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        }
    }

    /** Program code run by a library initializer has the limit like any other. */
    static void programCodeInLibraryInitializer() {
        String key = "java.util.concurrent.ForkJoinPool.common.threadFactory";
        System.setProperty(key, Factory.class.getName());
        ForkJoinPool.commonPool();
        long max = Runtime.getRuntime().maxMemory();
        System.out.println("initializer stayed within the limit: " + (Factory.inInitializer <= max));
        System.out.println("constructor stayed within the limit: " + (Factory.fromConstructor <= max));
    }

    /** Natives may take the heap past what exception creation may use. */
    static void libraryClassFirstUsedWhenNothingIsLeft() {
        byte[] half = new byte[4 << 20];
        Object[] clones = new Object[8];
        fill();
        try {
            clones[0] = half.clone();
            clones[1] = half.clone();
        } catch (OutOfMemoryError e) {
            clones[2] = half;
        }
        try {
            clones[3] = Base64.getEncoder();
        } catch (Throwable e) {
            clones[3] = e;
        }
        full = null;
        clones = null;
        half = null;
        System.out.println("later use: " + Base64.getEncoder().encodeToString(new byte[] {1, 2}));
    }

    public static void main(String[] args) {
        errorsOfTheVm();
        retained();
        programCodeInLibraryInitializer();
        libraryClassFirstUsedWhenNothingIsLeft();
        System.out.println("done");
    }
}
