package lazy.ui;

import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A message box, a question, or a prompt for a line of text, made by {@link Window#message},
 * {@link Window#confirm} and {@link Window#prompt}. It shows over its window, which takes no
 * input meanwhile; {@link #show} returns at once, and the answer comes to {@link #onClose} and to
 * the stage {@code show} returns.
 */
public final class Dialog extends Popup {
    private final int style;
    private String title, text;
    private final String initial;
    private String accept, cancel;
    private Consumer<String> onClose;

    Dialog(Window window, int style, String title, String text, String initial) {
        super(window);
        this.style = style;
        this.title = Ui.nonNull(title, "title");
        this.text = Ui.nonNull(text, "text");
        this.initial = initial;
    }

    /** Names the button that accepts, before the first {@link #show}. */
    public Dialog acceptLabel(String label) {
        fixed();
        accept = Ui.nonNull(label, "label");
        return this;
    }

    /** Names the button that cancels, before the first {@link #show}. */
    public Dialog cancelLabel(String label) {
        fixed();
        cancel = Ui.nonNull(label, "label");
        return this;
    }

    /** Changes the title. */
    public Dialog title(String title) {
        Ui.nonNull(title, "title");
        if (mounted()) call(Op.TITLE, title);
        this.title = title;
        return this;
    }

    /** Changes the message. */
    public Dialog text(String text) {
        Ui.nonNull(text, "text");
        if (mounted()) call(Op.MESSAGE, text);
        this.text = text;
        return this;
    }

    /**
     * Runs {@code action} when the dialog closes: with the prompt's text (empty for a message or
     * a question) when accepted, with null when cancelled.
     */
    public Dialog onClose(Consumer<String> action) {
        onClose = Ui.nonNull(action, "action");
        return this;
    }

    /**
     * Shows the dialog. The stage completes with the answer {@link #onClose} gets, after it; it
     * is cancelled if the window closes first. While another dialog of the window is open, this
     * shows nothing and the stage is cancelled.
     */
    public CompletionStage<String> show() {
        return showModal();
    }

    /** Closes the dialog, if open, as if cancelled: {@link #onClose} and the stage get null. */
    public void close() {
        if (mounted()) call(Op.CLOSE, null);
        closed(onClose, null);
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.DIALOG, this);
        out.op(style);
        out.string(title);
        out.string(text);
        out.string(initial);
        out.string(accept);
        out.string(cancel);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind != Ui.DIALOG) return;
        closed(onClose, i == -1 ? null : s == null ? "" : s);
    }
}
