package lazy.ui;

import java.util.ArrayList;
import java.util.function.IntConsumer;

/**
 * A dialog with a row of command buttons and Cancel, made by {@link Window#task}: "Save changes?"
 * with Save and Discard. {@link #show} returns at once; the choice comes to {@link #onChoice}.
 */
public final class TaskDialog extends Free {
    /** Icons for {@link #icon}. */
    public static final int NONE = 0, INFO = 1, WARNING = 2, ERROR = 3;

    private final String title, text;
    private int icon;
    private final ArrayList<String> commands = new ArrayList<String>();
    private String verification;
    private IntConsumer onChoice;

    TaskDialog(Window window, String title, String text) {
        super(window);
        this.title = Ui.nonNull(title, "title");
        this.text = Ui.nonNull(text, "text");
    }

    /** Shows {@link #INFO}, {@link #WARNING} or {@link #ERROR}, before the first show. */
    public TaskDialog icon(int icon) {
        fixed();
        if (icon < NONE || icon > ERROR) throw new IllegalArgumentException("icon " + icon);
        this.icon = icon;
        return this;
    }

    /** Adds a command button; the first is number 0. Before the first show. */
    public TaskDialog command(String label) {
        fixed();
        commands.add(Ui.nonNull(label, "label"));
        return this;
    }

    /** Adds a check box under the commands, read with {@link #verified}. */
    public TaskDialog verification(String label) {
        fixed();
        verification = Ui.nonNull(label, "label");
        return this;
    }

    /** Whether the verification box was checked. */
    public boolean verified() {
        return mounted() && callInt(Op.GET_CHECKED) != 0;
    }

    /** Runs {@code action} with the command chosen, or -1 for Cancel. */
    public TaskDialog onChoice(IntConsumer action) {
        onChoice = Ui.nonNull(action, "action");
        return this;
    }

    /** Shows the dialog; does nothing while another dialog of the window is open. */
    public void show() {
        showModal();
    }

    public void close() {
        if (mounted()) call(Op.CLOSE, null);
        closed();
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.TASK, this);
        out.string(title);
        out.string(text);
        out.op(icon);
        out.strings(commands.toArray(new String[0]));
        out.string(verification);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind != Ui.DIALOG) return;
        closed();
        if (onChoice != null) onChoice.accept(i);
    }
}
