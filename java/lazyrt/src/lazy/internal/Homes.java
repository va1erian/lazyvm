package lazy.internal;

import java.util.concurrent.Executor;

/**
 * Executors that own a thread other than the loop's, by thread: the UI thread's, which {@code
 * lazy.ui} registers while {@code Ui.run} runs. {@link lazy.async.Loop#home} reads it, so that
 * code resuming after an {@code await} goes back to the thread it started on (docs/siesta-async.md
 * section 4) without {@code lazy.async} depending on {@code lazy.ui}.
 */
public final class Homes {
    private static volatile Thread thread;
    private static volatile Executor executor;

    private Homes() {}

    /** Makes {@code e} the home of code running on {@code t}, or clears it when {@code e} is null. */
    public static synchronized void register(Thread t, Executor e) {
        executor = e;
        thread = e == null ? null : t;
    }

    /** The executor registered for {@code t}, or null. */
    public static Executor of(Thread t) {
        Executor e = executor;
        return t == thread ? e : null;
    }
}
