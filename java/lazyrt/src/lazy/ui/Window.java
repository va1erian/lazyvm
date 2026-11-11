package lazy.ui;

import java.util.ArrayList;

/**
 * A top-level window: a title, a size in design units and one layout as its content. The window
 * {@link Ui#run} opens is the main one; closing it ends the loop. Others open from callbacks with
 * {@link #open} and close on their own.
 */
public final class Window extends Node {
    /** Modifier bits for {@link #shortcut}. Ctrl also stands for the Windows or Command key. */
    public static final int CTRL = 1, SHIFT = 2, ALT = 4;
    /** Key codes for {@link #shortcut} beyond letters and digits. */
    public static final char ENTER = '\r', TAB = '\t', ESCAPE = 0x1b, DELETE = 0x2e;

    String title;
    final int width, height;
    Layout content;
    private Runnable onClose;
    private final ArrayList<Integer> keys = new ArrayList<Integer>();
    private final ArrayList<Runnable> actions = new ArrayList<Runnable>();
    /** Dialogs, menus and timers made in this window, forgotten when it closes. */
    final ArrayList<Node> owned = new ArrayList<Node>();

    Window(String title, int width, int height) {
        this.title = Ui.nonNull(title, "title");
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192)
            throw new IllegalArgumentException("window size " + width + "x" + height);
        this.width = width;
        this.height = height;
    }

    /** Shows {@code layout} as the window's content, before the window opens. */
    public Window content(Layout layout) {
        if (mounted()) throw new IllegalStateException("the content is fixed once the window opens");
        this.content = Ui.nonNull(layout, "layout");
        return this;
    }

    public String title() {
        return title;
    }

    /** Retitles the window. */
    public Window title(String title) {
        Ui.nonNull(title, "title");
        if (mounted() && !title.equals(this.title)) {
            live();
            Ui.title0(id, title);
        }
        this.title = title;
        return this;
    }

    /**
     * Runs {@code action} when the user asks to close the window, instead of closing it: the
     * action decides, and calls {@link #close} to go on.
     */
    public Window onClose(Runnable action) {
        this.onClose = Ui.nonNull(action, "action");
        return this;
    }

    /**
     * Runs {@code action} when {@code key} is pressed with exactly {@code modifiers} ({@link
     * #CTRL}, {@link #SHIFT}, {@link #ALT}), whatever has the focus. The focused widget still sees
     * the key. {@code key} is a letter, a digit or one of this class's key codes. Set shortcuts
     * before the window opens.
     */
    public Window shortcut(char key, int modifiers, Runnable action) {
        if (mounted()) throw new IllegalStateException("set shortcuts before the window opens");
        if (modifiers < 0 || modifiers > (CTRL | SHIFT | ALT))
            throw new IllegalArgumentException("modifiers " + modifiers);
        char k = Character.toUpperCase(key);
        boolean named = k == ENTER || k == TAB || k == ESCAPE || k == DELETE;
        if (!named && !(k >= 'A' && k <= 'Z') && !(k >= '0' && k <= '9'))
            throw new IllegalArgumentException("key " + (int) key);
        keys.add((int) k);
        keys.add(modifiers);
        actions.add(Ui.nonNull(action, "action"));
        return this;
    }

    int[] keys() {
        int[] out = new int[keys.size()];
        for (int i = 0; i < out.length; i++) out[i] = keys.get(i);
        return out;
    }

    /** Opens this window beside the main one, from a callback. */
    public void open() {
        Ui.check();
        if (!Ui.running) throw new IllegalStateException("open the main window with Ui.run");
        if (content == null) throw new IllegalStateException("window has no content");
        Encoder out = encode();
        try {
            Ui.open0(Ui.main.id, id, title, width, height, out.ops(), out.objects(), keys());
        } catch (RuntimeException e) {
            out.abandon();
            Ui.free(this);
            throw e;
        }
    }

    /** Closes the window; closing the main window ends the loop. */
    public void close() {
        if (!mounted()) return;
        live();
        int was = id;
        Ui.close0(was);
        unmount();
    }

    /** A message box: {@code text} and an OK button. */
    public Dialog message(String title, String text) {
        return new Dialog(this, 0, title, text, null);
    }

    /** A question with OK and Cancel. */
    public Dialog confirm(String title, String text) {
        return new Dialog(this, 1, title, text, null);
    }

    /** A question with a text field, starting with {@code initial}. */
    public Dialog prompt(String title, String text, String initial) {
        return new Dialog(this, 2, title, text, Ui.nonNull(initial, "initial"));
    }

    /** A dialog with command buttons, added with {@link TaskDialog#command}. */
    public TaskDialog task(String title, String text) {
        return new TaskDialog(this, title, text);
    }

    /** A picker for a file to open. */
    public FileDialog openFile(String title) {
        return new FileDialog(this, false, title);
    }

    /** A picker for a file to save to. */
    public FileDialog saveFile(String title) {
        return new FileDialog(this, true, title);
    }

    /** A context menu, filled with {@link Menu#item} and shown with {@link Menu#show}. */
    public Menu menu() {
        return new Menu(this);
    }

    Encoder encode() {
        Encoder out = new Encoder();
        Ui.register(this);
        Ui.building = this;
        try {
            content.encode(out);
        } catch (RuntimeException e) {
            out.abandon();
            Ui.free(this);
            throw e;
        } finally {
            Ui.building = null;
        }
        return out;
    }

    private void unmount() {
        content.unmount();
        int[] ids = new int[owned.size()];
        for (int i = 0; i < ids.length; i++) {
            Node n = owned.get(i);
            ids[i] = n.id;
            Ui.free(n);
        }
        owned.clear();
        if (ids.length > 0) Ui.forget0(ids);
        Ui.free(this);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (kind == Ui.CLOSE) {
            if (onClose != null) onClose.run();
            else close();
        } else if (kind == Ui.KEY && i >= 0 && i < actions.size()) {
            actions.get(i).run();
        }
    }
}
