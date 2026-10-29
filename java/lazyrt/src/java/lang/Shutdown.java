package java.lang;

import java.util.IdentityHashMap;
import lazy.internal.VM;

/**
 * Shutdown hooks and the exit sequence (Runtime.addShutdownHook Javadoc).
 * The VM calls {@code runHooks} when the last non-daemon thread has ended,
 * but only if this class was loaded: no hook, no cost.
 */
final class Shutdown {
    private static final IdentityHashMap<Thread, Thread> hooks = new IdentityHashMap<Thread, Thread>();
    private static boolean hooksStarted;
    private static boolean exiting;

    private Shutdown() {
    }

    static synchronized void add(Thread hook) {
        if (hook == null) {
            throw new NullPointerException();
        }
        if (hooksStarted) {
            throw new IllegalStateException("Shutdown in progress");
        }
        if (hook.isAlive()) {
            throw new IllegalArgumentException("Hook already running");
        }
        if (hooks.containsKey(hook)) {
            throw new IllegalArgumentException("Hook previously registered");
        }
        hooks.put(hook, hook);
    }

    static synchronized boolean remove(Thread hook) {
        if (hooksStarted) {
            throw new IllegalStateException("Shutdown in progress");
        }
        if (hook == null) {
            throw new NullPointerException();
        }
        return hooks.remove(hook) != null;
    }

    /**
     * The VM's shutdown after the last non-daemon thread: the hooks, unless
     * a System.exit is already under way, in which case this thread waits
     * for it to end the VM.
     */
    static void runHooks() {
        Thread[] list = begin();
        if (list == null) {
            blockForever();
        }
        run(list);
    }

    /** System.exit: the hooks, then the end. Any later caller blocks forever. */
    static void exit(int status) {
        Thread[] list = begin();
        if (list == null) {
            blockForever();
        }
        run(list);
        VM.exit(status);
    }

    /** The hooks to run if this is the first shutdown, or null. */
    private static synchronized Thread[] begin() {
        if (exiting) {
            return null;
        }
        exiting = true;
        hooksStarted = true;
        return hooks.keySet().toArray(new Thread[0]);
    }

    /**
     * Starts every hook, then waits for all of them. A hook that cannot start
     * (it already ran) ends the sequence with its exception, as on Java 8.
     */
    private static void run(Thread[] list) {
        for (Thread hook : list) {
            hook.start();
        }
        for (Thread hook : list) {
            joinUninterruptibly(hook);
        }
    }

    private static void joinUninterruptibly(Thread t) {
        while (true) {
            try {
                t.join();
                return;
            } catch (InterruptedException e) {
                // Shutdown goes on; the hooks still get their chance.
            }
        }
    }

    private static void blockForever() {
        Object never = new Object();
        synchronized (never) {
            while (true) {
                try {
                    never.wait();
                } catch (InterruptedException e) {
                    // Exit is under way in another thread.
                }
            }
        }
    }
}
