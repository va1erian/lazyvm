package lazy.ui;

/** A line of text. */
public final class Label extends Widget {
    private String text;

    Label(String text) {
        this.text = Ui.nonNull(text, "text");
    }

    /** The text shown. */
    public String text() {
        return text;
    }

    /** Shows {@code text} instead. */
    public Label text(String text) {
        this.text = Ui.nonNull(text, "text");
        if (mounted()) Ui.setText0(id, text);
        return this;
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.LABEL, this);
        out.string(text);
    }
}
