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
        super.unmount();
        content.unmount();
    }
}
