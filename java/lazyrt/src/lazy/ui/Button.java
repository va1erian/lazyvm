package lazy.ui;

/**
 * A push button: a caption, an icon, or both. Icons are Lucide names as xui spells them
 * ({@code "FilePlus"}, {@code "Bold"}).
 */
public final class Button extends Widget {
    private String text, icon;
    private boolean primary;
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
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.TEXT, text);
        this.text = text;
        return this;
    }

    /** Shows the Lucide icon {@code name}, before the window opens. */
    public Button icon(String name) {
        if (mounted()) throw new IllegalStateException("set the icon before the window opens");
        icon = Ui.nonNull(name, "name");
        return this;
    }

    /** Draws the button as the window's main action, before the window opens. */
    public Button primary() {
        if (mounted()) throw new IllegalStateException("set primary before the window opens");
        primary = true;
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
        head(out, Encoder.BUTTON);
        out.string(text);
        out.string(icon);
        out.flag(primary);
        out.flag(onClick != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.CLICK && onClick != null) onClick.run();
    }
}
