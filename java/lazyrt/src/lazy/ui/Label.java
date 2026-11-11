package lazy.ui;

/** A line of text. */
public final class Label extends Widget {
    private String text;
    private int style;

    Label(String text) {
        this.text = Ui.nonNull(text, "text");
    }

    /** The text shown. */
    public String text() {
        return text;
    }

    /** Shows {@code text} instead. */
    public Label text(String text) {
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.TEXT, text);
        this.text = text;
        return this;
    }

    /** Shows the text large, as a heading, before the window opens. */
    public Label title() {
        return style(1);
    }

    /** Shows the text small, as a caption, before the window opens. */
    public Label caption() {
        return style(2);
    }

    private Label style(int s) {
        if (mounted()) throw new IllegalStateException("set the style before the window opens");
        style = s;
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.LABEL);
        out.string(text);
        out.op(style);
    }
}
