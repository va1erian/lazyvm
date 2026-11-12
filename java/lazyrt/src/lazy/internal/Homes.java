package lazy.internal;

import java.util.concurrent.Executor;

/**
 * Executors that own a thread other than the loop's, by thread: the UI thread's, which {@code
 * lazy.ui} registers while {@code Ui.run} runs. {@link lazy.async.Loop#home} reads it, so that
 * code resuming after an {@code await} goes back to the thread it started on (docs/siesta-async.md
 * section 4) without {@code lazy.async} depending on {@code lazy.ui}.
 */
public final class Homes {
    /** The thread and its executor, published together so a reader never mixes two registrations. */
    private static volatile Home home;

    private Homes() {}

    /** Makes {@code e} the home of code running on {@code t}, or clears it when {@code e} is null. */
    public static void register(Thread t, Executor e) {
        home = e == null ? null : new Home(t, e);
    }

    /** The executor registered for {@code t}, or null. */
    public static Executor of(Thread t) {
        Home h = home;
        return h != null && h.thread == t ? h.executor : null;
    }

    private static final class Home {
        final Thread thread;
        final Executor executor;

        Home(Thread thread, Executor executor) {
            this.thread = thread;
            this.executor = executor;
        }
    }
}
