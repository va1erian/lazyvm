package lazy.ui;

/**
 * A widget: before its window opens it records options, and after that the same methods change
 * it on screen (docs/ui.md). A widget belongs to one window and is mounted once.
 *
 * <p>How a widget is sized in its layout ({@link #fill}, {@link #fixed}, {@link #width}, ...) is
 * fixed once its window opens; the last one set wins. These methods return {@code Widget}, so set
 * a widget's own options first.
 */
public abstract class Widget extends Node {
    private static final int FILL = 1, FIXED = 2, MIN = 3, WIDTH = 4, HEIGHT = 5, SIZE = 6;
    private static final int DISABLED = 1, HIDDEN = 2, TOOLTIP = 4;

    private int sizing, a, b;
    private boolean enabled = true, visible = true;
    private String tooltip;
    /** The window this widget is shown in, once mounted. */
    Window window;

    Widget() {}

    /** Takes a share {@code weight} of the space its layout has left over. */
    public Widget fill(int weight) {
        if (weight < 0 || weight > 1000) throw new IllegalArgumentException("weight " + weight);
        return sized(FILL, weight, 0);
    }

    /** Exactly {@code size} design units along its layout's direction. */
    public Widget fixed(int size) {
        return sized(FIXED, length(size), 0);
    }

    /** At least {@code size} design units along its layout's direction. */
    public Widget min(int size) {
        return sized(MIN, length(size), 0);
    }

    /** Exactly {@code width} design units wide. */
    public Widget width(int width) {
        return sized(WIDTH, length(width), 0);
    }

    /** Exactly {@code height} design units tall. */
    public Widget height(int height) {
        return sized(HEIGHT, length(height), 0);
    }

    /** Exactly {@code width} by {@code height} design units. */
    public Widget size(int width, int height) {
        return sized(SIZE, length(width), length(height));
    }

    private Widget sized(int kind, int a, int b) {
        if (mounted()) throw new IllegalStateException("sizes are fixed once the window opens");
        sizing = kind;
        this.a = a;
        this.b = b;
        return this;
    }

    static int length(int v) {
        if (v < 0 || v > Layout.MAX_LENGTH) throw new IllegalArgumentException("length " + v);
        return v;
    }

    /** Whether the widget takes input. */
    public boolean enabled() {
        return enabled;
    }

    /** Lets the widget take input, or greys it out. */
    public Widget enabled(boolean on) {
        if (mounted()) call(Op.ENABLED, on ? 1 : 0, 0, null);
        enabled = on;
        return this;
    }

    /** Whether the widget is shown. */
    public boolean visible() {
        return visible;
    }

    /** Shows or hides the widget. */
    public Widget visible(boolean on) {
        if (mounted()) call(Op.VISIBLE, on ? 1 : 0, 0, null);
        visible = on;
        return this;
    }

    /** The text shown when the pointer rests on the widget, or null. */
    public String tooltip() {
        return tooltip;
    }

    /**
     * Shows {@code text} when the pointer rests on the widget. A widget built without a tooltip
     * cannot get one once its window is open.
     */
    public Widget tooltip(String text) {
        Ui.nonNull(text, "text");
        if (mounted()) {
            if (tooltip == null)
                throw new IllegalStateException("give the widget a tooltip before its window opens");
            call(Op.TOOLTIP, text);
        }
        tooltip = text;
        return this;
    }

    /** Gives the keyboard focus to the widget. */
    public void focus() {
        call(Op.FOCUS, null);
    }

    /** Appends this widget, and its children, to the op list. */
    abstract void encode(Encoder out);

    /** Starts this widget's node: its kind, id, sizing and flags. */
    final void head(Encoder out, int kind) {
        out.node(kind, this);
        window = Ui.building;
        out.op(sizing);
        out.op(a);
        out.op(b);
        int flags = (enabled ? 0 : DISABLED) | (visible ? 0 : HIDDEN) | (tooltip != null ? TOOLTIP : 0);
        out.op(flags);
        if (tooltip != null) out.string(tooltip);
    }

    /** Leaves the screen: its window closed, or the panel it was in got new content. */
    void unmount() {
        Ui.free(this);
        window = null;
    }
}
