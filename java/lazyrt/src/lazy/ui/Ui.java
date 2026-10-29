package lazy.ui;

/**
 * The entry to {@code lazy.ui}: widget factories and the loop (docs/ui.md).
 *
 * Build a window once from widgets, then change them from callbacks. Callbacks run on the
 * thread that called {@link #run}, one at a time. {@code lazy.ui} and {@code lazy.gfx} windows
 * cannot be used in the same process.
 */
public final class Ui {
    /** The event kind of a click, as the VM passes it to {@link #dispatch}. */
    static final int CLICK = 1;

    private static Widget[] registry = new Widget[64];
    private static int count;
    private static boolean ran;

    private Ui() {}

    /** A window titled {@code title}, {@code width} by {@code height} design units. */
    public static Window window(String title, int width, int height) {
        return new Window(title, width, height);
    }

    /** A column of widgets, top to bottom. */
    public static Layout column() {
        return new Layout(Encoder.COLUMN);
    }

    /** A row of widgets, left to right. */
    public static Layout row() {
        return new Layout(Encoder.ROW);
    }

    /** A line of text. */
    public static Label label(String text) {
        return new Label(text);
    }

    /** A push button with {@code text} as its caption. */
    public static Button button(String text) {
        return new Button(text);
    }

    /**
     * Opens {@code window} and runs it until it closes. Call it once per process, from the main
     * thread. An exception thrown by a callback closes the window and is rethrown from here.
     *
     * @throws IllegalStateException on a second call, or with no window system (use {@code
     *     -Dlazy.ui=headless})
     */
    public static void run(Window window) {
        nonNull(window, "window");
        if (window.content == null) throw new IllegalStateException("window has no content");
        if (ran) throw new IllegalStateException("Ui.run runs once per process");
        ran = true;
        Encoder out = new Encoder();
        window.content.encode(out);
        run0(window.title, window.width, window.height, out.ops(), out.strings.toArray());
    }

    static int register(Widget w) {
        if (count + 1 == registry.length) {
            Widget[] grown = new Widget[registry.length * 2];
            System.arraycopy(registry, 0, grown, 0, registry.length);
            registry = grown;
        }
        registry[++count] = w;
        return count;
    }

    /** The one upcall: the VM calls it for each widget message. */
    private static void dispatch(int id, int kind) {
        if (id > 0 && id <= count) registry[id].fire(kind);
    }

    static <T> T nonNull(T value, String what) {
        if (value == null) throw new NullPointerException(what);
        return value;
    }

    private static native void run0(String title, int width, int height, int[] ops, Object[] strings);

    static native void setText0(int id, String text);
}
