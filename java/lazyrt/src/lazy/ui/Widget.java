package lazy.ui;

/**
 * A widget: before its window opens it records options, and after that the same methods change
 * it on screen (docs/ui.md). A widget belongs to one window and is mounted once.
 */
public abstract class Widget {
    /** The registry id, 0 until the window opens. */
    int id;

    Widget() {}

    /** Appends this widget, and its children, to the op list. */
    abstract void encode(Encoder out);

    /** Handles a message for this widget; {@code kind} is one of the {@code Ui} event kinds. */
    void fire(int kind) {}

    final boolean mounted() {
        return id != 0;
    }
}
