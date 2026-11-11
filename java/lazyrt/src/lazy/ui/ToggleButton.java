package lazy.ui;

import java.util.function.Consumer;

/** A button that stays pressed until clicked again: a caption, a Lucide icon, or both. */
public final class ToggleButton extends Widget {
    private String text, icon;
    private boolean checked;
    private Consumer<Boolean> onToggle;

    ToggleButton(String text) {
        this.text = Ui.nonNull(text, "text");
    }

    /** Shows the Lucide icon {@code name}, before the window opens. */
    public ToggleButton icon(String name) {
        if (mounted()) throw new IllegalStateException("set the icon before the window opens");
        icon = Ui.nonNull(name, "name");
        return this;
    }

    /**
     * Whether it is on. The user changes it, so it is read from the window, unless {@link
     * #onToggle} keeps this side's copy current.
     */
    public boolean checked() {
        return mounted() && onToggle == null ? callInt(Op.GET_CHECKED) != 0 : checked;
    }

    /** Presses or releases the button, without running the toggle action. */
    public ToggleButton checked(boolean on) {
        // With onToggle this side knows what is shown, and skips a change to the same state.
        if (mounted() && (onToggle == null || on != checked)) call(Op.CHECKED, on ? 1 : 0, 0, null);
        checked = on;
        return this;
    }

    /** Runs {@code action} with the new state when the user toggles the button. */
    public ToggleButton onToggle(Consumer<Boolean> action) {
        if (mounted()) throw new IllegalStateException("set onToggle before the window opens");
        onToggle = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, Encoder.TOGGLE);
        out.string(text);
        out.string(icon);
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
