package lazy.ui;

/** A top-level window: a title, a size in design units and one layout as its content. */
public final class Window {
    final String title;
    final int width, height;
    Layout content;

    Window(String title, int width, int height) {
        this.title = Ui.nonNull(title, "title");
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("window size " + width + "x" + height);
        this.width = width;
        this.height = height;
    }

    /** Shows {@code layout} as the window's content. */
    public Window content(Layout layout) {
        this.content = Ui.nonNull(layout, "layout");
        return this;
    }
}
