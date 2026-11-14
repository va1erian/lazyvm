package lazy.internal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Executors that own a thread other than the loop's, by thread: the UI thread's, which {@code
 * lazy.ui} registers while {@code Ui.run} runs. {@link lazy.async.Loop#home} reads it, so that
 * code resuming after an {@code await} goes back to the thread it started on (docs/siesta-async.md
 * section 4) without {@code lazy.async} depending on {@code lazy.ui}. A home also owns what code on
 * its thread {@link #adopt}s, such as a topic subscription, and ends it when it is cleared.
 */
public final class Homes {
    /** The thread and its executor, published together so a reader never mixes two registrations. */
    private static volatile Home home;

    private Homes() {}

    /**
     * Makes {@code e} the home of code running on {@code t}, or clears it when {@code e} is null;
     * either way, what the previous home adopted ends here.
     */
    public static void register(Thread t, Executor e) {
        Home old = home;
        home = e == null ? null : new Home(t, e);
        if (old != null) old.end();
    }

    /** Ties {@code close} to the current thread's home, if it has one: it runs when that home is cleared. */
    public static void adopt(Runnable close) {
        Home h = home;
        if (h == null || h.thread != Thread.currentThread()) return;
        synchronized (h.owned) {
            h.owned.add(close);
        }
    }

    /** Lets go of {@code close}, which ran or no longer needs to. */
    public static void release(Runnable close) {
        Home h = home;
        if (h == null) return;
        synchronized (h.owned) {
            h.owned.remove(close);
        }
    }

    /** The executor registered for {@code t}, or null. */
    public static Executor of(Thread t) {
        Home h = home;
        return h != null && h.thread == t ? h.executor : null;
    }

    private static final class Home {
        final Thread thread;
        final Executor executor;
        final Set<Runnable> owned = new LinkedHashSet<>();

        Home(Thread thread, Executor executor) {
            this.thread = thread;
            this.executor = executor;
        }

        /** Runs what was adopted, outside the lock: a closer releases itself. */
        void end() {
            List<Runnable> all;
            synchronized (owned) {
                all = new ArrayList<>(owned);
                owned.clear();
            }
            for (Runnable close : all) close.run();
        }
    }
}
