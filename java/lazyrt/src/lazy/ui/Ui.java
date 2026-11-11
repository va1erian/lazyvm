package lazy.ui;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;

/**
 * The entry to {@code lazy.ui}: widget factories, timers and the loop (docs/ui.md).
 *
 * <p>Build a window once from widgets, then change them from callbacks. Callbacks run on the
 * thread that called {@link #run}, one at a time; other threads hand work to it through {@link
 * #executor}. {@code lazy.ui} and {@code lazy.gfx} windows cannot be used in the same process.
 */
public final class Ui {
    // Event kinds, as the VM passes them to dispatch (crates/xui-bind/src/msg.rs).
    static final int CLICK = 1, TOGGLE = 2, SELECT = 3, CHANGE = 4, ACTIVATE = 5, SLIDE = 6;
    static final int TIMER = 7, CLOSE = 8, KEY = 9, DIALOG = 10, STYLE = 11, LINK = 12, WAKE = 13;

    // 65,535 live nodes and 32,768 generations a slot before an id repeats.
    private static final int SLOT_BITS = 16, SLOT_MASK = (1 << SLOT_BITS) - 1, GEN_MASK = 0x7fff;

    private static Node[] nodes = new Node[64];
    private static int[] gens = new int[64];
    private static int[] freeSlots = new int[64];
    private static int freeCount, used;

    private static boolean ran;
    private static final boolean headless = "headless".equals(System.getProperty("lazy.ui"));
    private static Thread uiThread;
    /** True while the loop runs. */
    static boolean running;
    /** The window whose widgets are being encoded. */
    static Window building;
    static Window main;
    /** The first exception a callback threw; Ui.run rethrows it. */
    private static Throwable failure;

    private static final ArrayDeque<Runnable> later = new ArrayDeque<Runnable>();
    private static final ArrayDeque<Runnable> queued = new ArrayDeque<Runnable>();
    private static boolean wakePending;
    private static final Executor EXECUTOR = new Executor() {
        @Override
        public void execute(Runnable task) {
            submit(nonNull(task, "task"));
        }
    };

    private Ui() {}

    static {
        lazy.internal.ui.UiAccess.set(new lazy.internal.ui.UiAccess.Ops() {
            @Override
            public int id(Object widget) {
                Node n = (Node) widget;
                n.live();
                return n.id;
            }

            @Override
            public boolean guard(Runnable step) {
                return Ui.guard(step);
            }

            @Override
            public void drain() {
                check();
                drainExecutor();
            }
        });
    }

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

    /** Widgets left to right, wrapping to a new line when the row is full. */
    public static Layout wrap() {
        return new Layout(Encoder.WRAP);
    }

    /** A line of text. */
    public static Label label(String text) {
        return new Label(text);
    }

    /** A push button with {@code text} as its caption. */
    public static Button button(String text) {
        return new Button(text);
    }

    /** A button that stays pressed until clicked again. */
    public static ToggleButton toggle(String text) {
        return new ToggleButton(text);
    }

    /** A check box captioned {@code text}. */
    public static CheckBox checkBox(String text) {
        return new CheckBox(text);
    }

    /** A one-line text field. */
    public static Edit edit() {
        return new Edit(false);
    }

    /** A text field of several lines. */
    public static Edit textArea() {
        return new Edit(true);
    }

    /** A drop-down list of {@code items}, the first one selected. */
    public static ComboBox comboBox(String... items) {
        return new ComboBox(items);
    }

    /** A list of rows, with no columns until {@link ListView#column} adds them. */
    public static ListView list() {
        return new ListView();
    }

    /** A slider from {@code min} to {@code max}. */
    public static Slider slider(int min, int max) {
        return new Slider(min, max);
    }

    /** A progress bar from 0 to {@code max}. */
    public static ProgressBar progress(int max) {
        return new ProgressBar(max);
    }

    /** A status bar with one part per string. */
    public static StatusBar statusBar(String... parts) {
        return new StatusBar(parts);
    }

    /** A toolbar, empty until {@link Toolbar#item} adds buttons. */
    public static Toolbar toolbar() {
        return new Toolbar();
    }

    /** A horizontal line between widgets. */
    public static Widget separator() {
        return new Plain(Encoder.SEPARATOR, false);
    }

    /** A vertical line between widgets in a row. */
    public static Widget verticalSeparator() {
        return new Plain(Encoder.SEPARATOR, true);
    }

    /** Empty space; give it a size, or it takes what is left over. */
    public static Widget spacer() {
        return new Plain(Encoder.SPACER, false);
    }

    /** A container whose content {@link Panel#set} replaces at any time. */
    public static Panel panel(Layout content) {
        return new Panel(content);
    }

    /** {@code content}, scrolled when it does not fit. */
    public static Scroll scroll(Layout content) {
        return new Scroll(content);
    }

    /** {@code content} in a frame titled {@code title}. */
    public static Group group(String title, Layout content) {
        return new Group(title, content);
    }

    /** Pages of content, one shown at a time, picked by tabs. */
    public static Tabs tabs() {
        return new Tabs();
    }

    /** A widget showing {@code drawing}, {@code width} by {@code height} design units. */
    public static Canvas canvas(int width, int height, Drawing drawing) {
        return new Canvas(width, height, drawing);
    }

    /** A rich-text editor: styled runs, paragraphs, lists, pictures, tables and pages. */
    public static RichText richText() {
        return new RichText();
    }

    /**
     * Opens {@code window} and runs it until the loop ends: its window is closed, or {@link
     * #quit} is called. Call it once per process, from the main thread. An exception thrown by a
     * callback closes every window and is rethrown from here.
     *
     * @throws IllegalStateException on a second call, with no window system (use {@code
     *     -Dlazy.ui=headless}), after a {@code lazy.gfx} window opened, or under the plain {@code
     *     lazyvm} launcher, which has no {@code lazy.ui} (run {@code lazyvm-ui})
     */
    public static void run(Window window) {
        nonNull(window, "window");
        if (window.content == null) throw new IllegalStateException("window has no content");
        if (ran) throw new IllegalStateException("Ui.run runs once per process");
        lazy.internal.Windowing.claim("lazy.ui");
        ran = true;
        uiThread = Thread.currentThread();
        main = window;
        Encoder out = window.encode();
        running = true;
        try {
            run0(window.id, window.title, window.width, window.height, out.ops(), out.objects(), window.keys());
        } catch (UnsatisfiedLinkError e) {
            // The plain lazyvm binary leaves xui out (docs/ui.md, "Size").
            throw new IllegalStateException("lazy.ui needs the lazyvm-ui launcher", e);
        } finally {
            running = false;
        }
        Throwable t = failure;
        failure = null;
        if (t != null) Ui.<RuntimeException>rethrow(t);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(Throwable t) throws T {
        throw (T) t;
    }

    /** Ends the loop: {@link #run} returns once the current callback does. */
    public static void quit() {
        check();
        if (running) quit0();
    }

    /** Runs {@code task} on the UI thread after the current callback returns. */
    public static void later(Runnable task) {
        check();
        later.add(nonNull(task, "task"));
    }

    /** Runs {@code task} once, {@code millis} milliseconds from now, on the UI thread. */
    public static Timer after(int millis, Runnable task) {
        return Timer.start(millis, task, false);
    }

    /** Runs {@code task} every {@code millis} milliseconds on the UI thread until cancelled. */
    public static Timer every(int millis, Runnable task) {
        return Timer.start(millis, task, true);
    }

    /**
     * An executor any thread may use to run work on the UI thread, in the order submitted. Work
     * submitted before {@link #run} runs once the window is open.
     */
    public static Executor executor() {
        return EXECUTOR;
    }

    /** The system clipboard's text, or null when it holds none. */
    public static String clipboard() {
        check();
        if (!running) throw new IllegalStateException("no window is open");
        return clipboard0(main.id, null);
    }

    /** Puts {@code text} on the system clipboard. */
    public static void clipboard(String text) {
        check();
        if (!running) throw new IllegalStateException("no window is open");
        clipboard0(main.id, nonNull(text, "text"));
    }

    private static void submit(Runnable task) {
        boolean wake;
        synchronized (queued) {
            queued.add(task);
            wake = !wakePending;
            wakePending = true;
        }
        if (!wake) return;
        boolean sent;
        try {
            sent = wake0();
        } catch (UnsatisfiedLinkError e) {
            // The plain lazyvm launcher: Ui.run will say so; the task waits.
            sent = false;
        }
        // Not sent: let the next submit try again rather than wait for a drain that never comes.
        if (!sent) synchronized (queued) {
            wakePending = false;
        }
    }

    /** Runs the executor's queue, in order, on the UI thread. */
    static void drainExecutor() {
        while (true) {
            Runnable task;
            synchronized (queued) {
                task = queued.poll();
                if (task == null) {
                    wakePending = false;
                    return;
                }
            }
            task.run();
        }
    }

    static void drainLater() {
        Runnable task;
        while ((task = later.poll()) != null) task.run();
    }

    /** Throws unless called on the UI thread, once the loop has a thread. */
    static void check() {
        if (uiThread != null && Thread.currentThread() != uiThread)
            throw new IllegalStateException("lazy.ui is used from its UI thread only; use Ui.executor()");
    }

    static void register(Node n) {
        int slot;
        if (freeCount > 0) {
            slot = freeSlots[--freeCount];
        } else {
            slot = ++used;
            if (slot > SLOT_MASK) throw new IllegalStateException("too many widgets");
            if (slot == nodes.length) grow();
        }
        nodes[slot] = n;
        n.id = gens[slot] << SLOT_BITS | slot;
    }

    private static void grow() {
        int size = nodes.length * 2;
        Node[] n = new Node[size];
        System.arraycopy(nodes, 0, n, 0, nodes.length);
        int[] g = new int[size];
        System.arraycopy(gens, 0, g, 0, gens.length);
        nodes = n;
        gens = g;
    }

    /** Gives {@code n}'s slot back; the next node in it gets a new generation. */
    static void free(Node n) {
        if (!n.mounted()) return;
        int slot = n.id & SLOT_MASK;
        if (nodes[slot] == n) {
            nodes[slot] = null;
            gens[slot] = (gens[slot] + 1) & GEN_MASK;
            if (freeCount == freeSlots.length) {
                int[] grown = new int[freeCount * 2];
                System.arraycopy(freeSlots, 0, grown, 0, freeCount);
                freeSlots = grown;
            }
            freeSlots[freeCount++] = slot;
        }
        n.id = 0;
    }

    /** The node {@code id} names, or null when it is gone (its generation moved on). */
    static Node lookup(int id) {
        int slot = id & SLOT_MASK;
        if (slot <= 0 || slot >= nodes.length) return null;
        Node n = nodes[slot];
        return n != null && n.id == id ? n : null;
    }

    /**
     * The one upcall: the VM calls it for each message. It keeps what a callback throws for
     * {@link #run} and returns false, which stops the loop for good.
     */
    private static boolean dispatch(int id, int kind, int i, double d, String s) {
        if (failure != null) return false;
        // A message means a window is up: from now on an uncaught exception is shown.
        if (!headless) lazy.internal.ui.ErrorReport.windowOpening();
        try {
            if (kind == WAKE) {
                drainExecutor();
            } else {
                Node n = lookup(id);
                if (n != null) n.fire(kind, i, d, s);
            }
            drainLater();
            return true;
        } catch (Throwable t) {
            // The first failure is the one Ui.run rethrows.
            if (failure == null) failure = t;
            return false;
        }
    }

    /** Called by the headless driver around its script: same rules as a callback. */
    static boolean guard(Runnable step) {
        if (failure != null) return false;
        try {
            drainExecutor();
            step.run();
            drainLater();
            return true;
        } catch (Throwable t) {
            // The first failure is the one Ui.run rethrows.
            if (failure == null) failure = t;
            return false;
        }
    }

    static <T> T nonNull(T value, String what) {
        if (value == null) throw new NullPointerException(what);
        return value;
    }

    private static native void run0(int window, String title, int width, int height, int[] ops, Object[] objects, int[] keys);

    static native void open0(int opener, int window, String title, int width, int height, int[] ops, Object[] objects, int[] keys);

    static native void close0(int window);

    private static native void quit0();

    static native void title0(int window, String title);

    static native void make0(int window, int[] ops, Object[] objects);

    static native void mount0(int panel, int[] ops, Object[] objects);

    static native void forget0(int[] ids);

    static native void timer0(int window, int id, int millis);

    static native Object call0(int id, int op, int i, double d, Object o);

    private static native String clipboard0(int window, String text);

    private static native boolean wake0();
}
