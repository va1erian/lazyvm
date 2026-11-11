package lazy.ui;

import java.util.function.Consumer;

/** A check box with a caption. */
public final class CheckBox extends Widget {
    private String text;
    private boolean checked;
    private Consumer<Boolean> onToggle;

    CheckBox(String text) {
        this.text = Ui.nonNull(text, "text");
    }

    public String text() {
        return text;
    }

    public CheckBox text(String text) {
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.TEXT, text);
        this.text = text;
        return this;
    }

    /**
     * Whether it is on. The user changes it, so it is read from the window, unless {@link
     * #onToggle} keeps this side's copy current.
     */
    public boolean checked() {
        return mounted() && onToggle == null ? callInt(Op.GET_CHECKED) != 0 : checked;
    }

    /** Checks or clears the box, without running the toggle action. */
    public CheckBox checked(boolean on) {
        // With onToggle this side knows what is shown, and skips a change to the same state.
        if (mounted() && (onToggle == null || on != checked)) call(Op.CHECKED, on ? 1 : 0, 0, null);
        checked = on;
        return this;
    }

    /** Runs {@code action} with the new state when the user toggles the box. */
    public CheckBox onToggle(Consumer<Boolean> action) {
        if (mounted()) throw new IllegalStateException("set onToggle before the window opens");
        onToggle = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.CHECK);
        out.string(text);
        out.flag(checked);
        out.flag(onToggle != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.TOGGLE && onToggle != null) {
            checked = i != 0;
            onToggle.accept(checked);
        }
    }
}
