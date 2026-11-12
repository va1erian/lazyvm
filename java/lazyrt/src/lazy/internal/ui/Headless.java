package lazy.internal.ui;

import lazy.ui.Ui;
import lazy.ui.Widget;
import lazy.ui.Window;

/**
 * Drives a window with no window system ({@code -Dlazy.ui=headless}): the test's own loop
 * (docs/ui.md, "Evidence and tests"). {@link #run} opens the window and runs the script once it
 * is built; each step injects input and lets the callbacks it causes run before returning.
 * Timers run on a manual clock that only {@link #advance} moves.
 */
public final class Headless {
    // Steps, shared with crates/natives-ui/src/headless.rs.
    private static final int CLICK = 1, HOVER = 2, SCROLL = 3, KEY = 4, TYPE = 5, ADVANCE = 6;
    private static final int CLOSE = 7, FRAME = 8, DUMP = 9, UPCALLS = 10, CLICK_AT = 11;

    private static Runnable script;
    private static Window main;

    private Headless() {}

    /** Opens {@code window} headless, runs {@code steps} once it is built, and returns. */
    public static void run(Window window, Runnable steps) {
        if (script != null) throw new IllegalStateException("one script per run");
        script = steps;
        main = window;
        Ui.run(window);
    }

    /**
     * Shapes every text with the font file {@code file} (a TrueType font) and no other, so that
     * pixels are the same on every platform. Call it before {@link #run}.
     */
    public static void font(byte[] file) {
        if (file == null) throw new NullPointerException("file");
        font0(file.clone());
    }

    /** {@link #font(byte[])} with the resource {@code name} next to class {@code anchor}. */
    public static void font(Class<?> anchor, String name) throws java.io.IOException {
        java.io.InputStream in = anchor.getResourceAsStream(name);
        if (in == null) throw new java.io.FileNotFoundException(name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            font(out.toByteArray());
        } finally {
            in.close();
        }
    }

    /** The VM's run hook: runs the script as a callback would. */
    private static void script() {
        final Runnable s = script;
        if (s != null) UiAccess.ops().guard(s);
    }

    private static int[] step(int op, int id, int i, String s) {
        return step0(op, id, i, s);
    }

    /** The registry id of mounted widget {@code w}: a slot in the low 16 bits, a generation above. */
    public static int id(Widget w) {
        return UiAccess.ops().id(w);
    }

    /** A left click in the middle of {@code w}. */
    public static void click(Widget w) {
        step(CLICK, id(w), 0, null);
    }

    /** A left click {@code x}, {@code y} pixels from {@code w}'s top-left corner. */
    public static void click(Widget w, int x, int y) {
        if (x < 0 || y < 0 || x > 0x7fff || y > 0xffff) throw new IllegalArgumentException(x + ", " + y);
        step(CLICK_AT, id(w), x << 16 | y, null);
    }

    /** The pointer moves over {@code w}. */
    public static void hover(Widget w) {
        step(HOVER, id(w), 0, null);
    }

    /** The wheel turns {@code notches} over {@code w}; positive scrolls up. */
    public static void scroll(Widget w, int notches) {
        step(SCROLL, id(w), notches * 120, null);
    }

    /** {@code key} (a letter, digit or {@link Window} key code) with {@link Window} modifiers. */
    public static void key(char key, int modifiers) {
        step(KEY, Character.toUpperCase(key), modifiers, null);
    }

    /** Types {@code text} into whatever has the focus. */
    public static void type(String text) {
        step(TYPE, 0, 0, text);
    }

    /** Moves the manual clock on by {@code millis}, running the timers that fall due. */
    public static void advance(int millis) {
        step(ADVANCE, 0, millis, null);
    }

    /** Runs what other threads handed to {@code Ui.executor()}, in order, now. */
    public static void drain() {
        UiAccess.ops().drain();
    }

    /** Presses the main window's close button. */
    public static void close() {
        step(CLOSE, UiAccess.ops().id(main), 0, null);
    }

    /** The main window's pixels: {@code {width, height, checksum}}. */
    public static int[] frame() {
        return step(FRAME, 0, 0, null);
    }

    /** How many messages the VM has handed to Java so far in this run. */
    public static int upcalls() {
        return step(UPCALLS, 0, 0, null)[0];
    }

    /** Writes the main window's pixels to {@code path} as a binary PPM; returns {@link #frame}. */
    public static int[] dump(String path) {
        return step(DUMP, 0, 0, path);
    }

    private static native int[] step0(int op, int id, int i, String s);

    private static native void font0(byte[] file);
}
