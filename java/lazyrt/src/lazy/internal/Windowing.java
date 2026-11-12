package lazy.internal;

/**
 * Which window toolkit this process uses. {@code lazy.gfx} and {@code lazy.ui} both need the
 * window system's one event loop, so a process opens windows from one of them only (docs/ui.md,
 * "Backends").
 */
public final class Windowing {
    private static String owner;

    private Windowing() {}

    /**
     * Claims the window system for {@code toolkit}; claiming it again for the same one is fine.
     *
     * @throws IllegalStateException if the other toolkit already opened a window
     */
    public static synchronized void claim(String toolkit) {
        if (owner == null) owner = toolkit;
        else if (!owner.equals(toolkit))
            throw new IllegalStateException(toolkit + " cannot open windows: this process already uses " + owner);
    }
}
