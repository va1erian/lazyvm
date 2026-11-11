package lazy.internal.ui;

/**
 * What {@link Headless} needs from {@code lazy.ui}'s package-private side: {@code lazy.ui.Ui}
 * installs it when it is initialized.
 */
public final class UiAccess {
    /** The operations {@code lazy.ui} lends. */
    public interface Ops {
        /** The registry id of a mounted widget. */
        int id(Object widget);

        /** Runs {@code step} as a callback would: a throw stops the loop and Ui.run rethrows it. */
        boolean guard(Runnable step);

        /** Runs the tasks other threads handed to {@code Ui.executor()}. */
        void drain();
    }

    private static Ops ops;

    private UiAccess() {}

    public static void set(Ops o) {
        if (ops != null) throw new IllegalStateException("already set");
        ops = o;
    }

    static Ops ops() {
        if (ops == null) throw new IllegalStateException("lazy.ui is not initialized");
        return ops;
    }
}
