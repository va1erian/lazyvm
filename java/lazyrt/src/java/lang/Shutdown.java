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

    /** Starts every hook, then waits for all of them; only the first call does. */
    static void runHooks() {
        Thread[] list;
        synchronized (Shutdown.class) {
            if (hooksStarted) {
                return;
            }
            hooksStarted = true;
            list = hooks.keySet().toArray(new Thread[0]);
        }
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

    /** System.exit: the hooks, then the end. A second caller blocks forever. */
    static void exit(int status) {
        boolean first;
        synchronized (Shutdown.class) {
            first = !exiting;
            exiting = true;
        }
        if (!first) {
            blockForever();
        }
        runHooks();
        VM.exit(status);
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
