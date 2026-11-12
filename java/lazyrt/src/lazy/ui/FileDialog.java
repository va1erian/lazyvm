package lazy.ui;

import java.util.ArrayList;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * A picker for a file to open or save to, made by {@link Window#openFile} and {@link
 * Window#saveFile}. {@link #show} returns at once, and the path comes to {@link #onChoose} and to
 * the stage {@code show} returns.
 */
public final class FileDialog extends Popup {
    private final boolean save;
    private final String title;
    private String dir, name;
    private boolean existing;
    private final ArrayList<String> labels = new ArrayList<String>();
    private final ArrayList<String[]> extensions = new ArrayList<String[]>();
    private Consumer<String> onChoose;

    FileDialog(Window window, boolean save, String title) {
        super(window);
        this.save = save;
        this.title = Ui.nonNull(title, "title");
    }

    /** The folder the picker starts in. */
    public FileDialog folder(String dir) {
        Ui.nonNull(dir, "dir");
        if (mounted()) call(Op.DIR, dir);
        this.dir = dir;
        return this;
    }

    /** The file name a save picker suggests. */
    public FileDialog name(String name) {
        Ui.nonNull(name, "name");
        if (mounted()) call(Op.NAME, name);
        this.name = name;
        return this;
    }

    /** Accepts only files that exist, before the first show. */
    public FileDialog existing() {
        fixed();
        existing = true;
        return this;
    }

    /**
     * Adds a filter named {@code label} showing files with these extensions (without the dot),
     * or every file with none. The first filter added is the one shown first.
     */
    public FileDialog filter(String label, String... extensions) {
        fixed();
        labels.add(Ui.nonNull(label, "label"));
        String[] copy = extensions.clone();
        for (String e : copy) Ui.nonNull(e, "extension");
        this.extensions.add(copy);
        return this;
    }

    /** Runs {@code action} with the chosen path, or null when the picker is cancelled. */
    public FileDialog onChoose(Consumer<String> action) {
        onChoose = Ui.nonNull(action, "action");
        return this;
    }

    /**
     * Shows the picker. The stage completes with the path {@link #onChoose} gets, after it; it is
     * cancelled if the window closes first. While another dialog of the window is open, this
     * shows nothing and the stage is cancelled.
     */
    public CompletionStage<String> show() {
        return showModal();
    }

    /** Closes the picker, if open, as if cancelled: {@link #onChoose} and the stage get null. */
    public void close() {
        if (mounted()) call(Op.CLOSE, null);
        closed(onChoose, null);
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.FILE, this);
        out.flag(save);
        out.string(title);
        out.string(dir);
        out.string(name);
        out.flag(existing);
        out.op(labels.size());
        for (int i = 0; i < labels.size(); i++) {
            out.string(labels.get(i));
            out.strings(extensions.get(i));
        }
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind != Ui.DIALOG) return;
        closed(onChoose, i == -1 ? null : s);
    }
}
