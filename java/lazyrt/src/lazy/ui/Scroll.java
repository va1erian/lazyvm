package lazy.ui;

/** Content that scrolls when it does not fit; scrolling runs no bytecode. */
public final class Scroll extends Widget {
    private final Layout content;

    Scroll(Layout content) {
        this.content = Ui.nonNull(content, "content");
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.SCROLL);
        content.encode(out);
    }

    @Override
    void unmount() {
        // Children first: freed slots are reused last-in first-out, so a new tree
        // encoded in the same order takes the same slots.
        content.unmount();
        super.unmount();
    }
}
