package lazy.gfx;

/**
 * A top-level window that shows a buffer of pixels (docs/gfx.md).
 *
 * The program owns the loop: it draws into an {@code int[]}, calls {@link #present}, then drains
 * input with {@link #poll}. Nothing calls back into the program, so a game loop written for one
 * thread works unchanged. A window is not thread-safe; use it from the thread that opened it.
 */
public final class Window implements AutoCloseable {
    private int handle;

    private Window(int handle) {
        this.handle = handle;
    }

    /**
     * Opens a window whose client area is {@code width * scale} by {@code height * scale}
     * pixels. Under {@code -Dlazy.gfx=headless} no window is shown, and frames go to memory.
     */
    public static Window open(String title, int width, int height, int scale) {
        if (title == null) throw new NullPointerException("title");
        if (width <= 0 || height <= 0 || scale <= 0)
            throw new IllegalArgumentException("size " + width + "x" + height + " scale " + scale);
        if ((long) width * scale > 16384 || (long) height * scale > 16384)
            throw new IllegalArgumentException("window too large");
        return new Window(open0(title, width * scale, height * scale));
    }

    /**
     * Shows {@code width * height} pixels of {@code rgb} (0xRRGGBB, row by row), scaled by
     * nearest neighbour to fit the window and centred on black.
     */
    public void present(int[] rgb, int width, int height) {
        if (rgb == null) throw new NullPointerException("rgb");
        if (width <= 0 || height <= 0 || (long) width * height > rgb.length)
            throw new IllegalArgumentException("size " + width + "x" + height + " for " + rgb.length + " pixels");
        present0(live(), rgb, width, height);
    }

    /**
     * Moves the next pending input event into {@code e} and returns true, or returns false when
     * none is pending. Never blocks.
     */
    public boolean poll(Event e) {
        if (e == null) throw new NullPointerException("event");
        if (handle == 0) return false;
        return poll0(handle, e);
    }

    /** Whether the window is still open: false after {@link #close} or once the user closed it. */
    public boolean isOpen() {
        return handle != 0 && isOpen0(handle);
    }

    /** The client area's current width in pixels. */
    public int width() {
        return size0(live(), 0);
    }

    /** The client area's current height in pixels. */
    public int height() {
        return size0(live(), 1);
    }

    /**
     * The client area's pixels (0xRRGGBB, {@code width() * height()} of them) as last presented,
     * scaling and black bars included. For screenshots and tests.
     */
    public int[] capture() {
        return capture0(live());
    }

    public void setTitle(String title) {
        if (title == null) throw new NullPointerException("title");
        setTitle0(live(), title);
    }

    /** Destroys the window. Closing twice does nothing. */
    @Override
    public void close() {
        if (handle != 0) {
            close0(handle);
            handle = 0;
        }
    }

    private int live() {
        if (handle == 0) throw new IllegalStateException("window closed");
        return handle;
    }

    private static native int open0(String title, int width, int height);

    private static native void present0(int handle, int[] rgb, int width, int height);

    private static native boolean poll0(int handle, Event e);

    private static native boolean isOpen0(int handle);

    private static native int size0(int handle, int axis);

    private static native int[] capture0(int handle);

    private static native void setTitle0(int handle, String title);

    private static native void close0(int handle);
}
