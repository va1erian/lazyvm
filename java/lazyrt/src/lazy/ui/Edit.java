package lazy.ui;

import java.util.function.Consumer;

/**
 * A text field, of one line or several. Typing runs no bytecode unless {@link #onChange} is set;
 * {@link #text()} reads what the user typed from the window.
 */
public final class Edit extends Widget {
    private final boolean multiline;
    private String text = "", placeholder;
    private boolean password;
    private Consumer<String> onChange;

    Edit(boolean multiline) {
        this.multiline = multiline;
    }

    /** The text in the field. */
    public String text() {
        return mounted() ? (String) call(Op.GET_TEXT, null) : text;
    }

    /** Replaces the text in the field. */
    public Edit text(String text) {
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.TEXT, text);
        this.text = text;
        return this;
    }

    /** Grey text shown while the field is empty, before the window opens. One line only. */
    public Edit placeholder(String text) {
        oneLine();
        placeholder = Ui.nonNull(text, "text");
        return this;
    }

    /** Hides what is typed, before the window opens. One line only. */
    public Edit password() {
        oneLine();
        password = true;
        return this;
    }

    private void oneLine() {
        if (mounted()) throw new IllegalStateException("set this before the window opens");
        if (multiline) throw new IllegalStateException("a text area has no placeholder or password");
    }

    /** Runs {@code action} with the new text after each change. */
    public Edit onChange(Consumer<String> action) {
        if (mounted()) throw new IllegalStateException("set onChange before the window opens");
        onChange = Ui.nonNull(action, "action");
        return this;
    }

    @Override
    void encode(Encoder out) {
        head(out, multiline ? Encoder.TEXT_AREA : Encoder.EDIT);
        out.string(text);
        if (!multiline) {
            out.string(placeholder);
            out.flag(password);
        }
        out.flag(onChange != null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.CHANGE && onChange != null) onChange.accept(s == null ? "" : s);
    }
}
