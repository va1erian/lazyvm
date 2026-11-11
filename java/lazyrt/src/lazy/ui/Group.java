package lazy.ui;

/** Content in a frame with a title. */
public final class Group extends Widget {
    private final String title;
    private final Layout content;

    Group(String title, Layout content) {
        this.title = Ui.nonNull(title, "title");
        this.content = Ui.nonNull(content, "content");
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.GROUP);
        out.string(title);
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
