package lazy.ui;

/** A push button. */
public final class Button extends Widget {
    private String text;
    private Runnable onClick;

    Button(String text) {
        this.text = Ui.nonNull(text, "text");
    }

    /** The caption. */
    public String text() {
        return text;
    }

    /** Shows {@code text} as the caption instead. */
    public Button text(String text) {
        this.text = Ui.nonNull(text, "text");
        if (mounted()) Ui.setText0(id, text);
        return this;
    }

    /** Runs {@code action} on the UI thread at each click. Set it before the window opens. */
    public Button onClick(Runnable action) {
        if (mounted()) throw new IllegalStateException("set onClick before the window opens");
        this.onClick = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.BUTTON, this);
        out.string(text);
        out.op(onClick != null ? 1 : 0);
    }

    @Override
    void fire(int kind) {
        if (kind == Ui.CLICK && onClick != null) onClick.run();
    }
}
