package lazy.ui;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * A context menu, made by {@link Window#menu} and shown under a widget with {@link #show}.
 * Entries are numbered in the order they are added, from 0, submenus and their entries included;
 * {@link #enabled} and {@link #checked} take that number.
 */
public final class Menu extends Free {
    private static final int ITEM = 0, CHECK = 1, SEPARATOR = 2, SUBMENU = 3;

    /** Per entry: type, text, icon, checked, and the entry's action. */
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private int depth;

    private static final class Entry {
        final int type, depth;
        final String text, icon;
        boolean checked, enabled = true;
        Runnable click;
        Consumer<Boolean> toggle;
        int children;

        Entry(int type, int depth, String text, String icon) {
            this.type = type;
            this.depth = depth;
            this.text = text;
            this.icon = icon;
        }
    }

    Menu(Window window) {
        super(window);
    }

    private Entry add(int type, String text, String icon) {
        fixed();
        Entry e = new Entry(type, depth, text, icon);
        entries.add(e);
        return e;
    }

    /** Adds an entry running {@code action}. */
    public Menu item(String text, Runnable action) {
        return item(text, null, action);
    }

    /** Adds an entry with the Lucide icon {@code icon}. */
    public Menu item(String text, String icon, Runnable action) {
        add(ITEM, Ui.nonNull(text, "text"), icon).click = Ui.nonNull(action, "action");
        return this;
    }

    /** Adds a switch, running {@code action} with its new state. */
    public Menu check(String text, boolean checked, Consumer<Boolean> action) {
        Entry e = add(CHECK, Ui.nonNull(text, "text"), null);
        e.checked = checked;
        e.toggle = Ui.nonNull(action, "action");
        return this;
    }

    public Menu separator() {
        add(SEPARATOR, "", null);
        return this;
    }

    /** Adds a submenu; {@code fill} adds its entries to this menu, nested. */
    public Menu submenu(String text, String icon, Consumer<Menu> fill) {
        Entry e = add(SUBMENU, Ui.nonNull(text, "text"), icon);
        int before = entries.size();
        depth++;
        try {
            fill.accept(this);
        } finally {
            depth--;
        }
        for (int i = before; i < entries.size(); i++) if (entries.get(i).depth == e.depth + 1) e.children++;
        return this;
    }

    /** Enables or greys out entry {@code index}. */
    public Menu enabled(int index, boolean on) {
        Entry e = entry(index);
        if (mounted()) call(Op.ITEM_ENABLED, index, on ? 1 : 0, null);
        e.enabled = on;
        return this;
    }

    /** Checks or clears the switch at {@code index}, without running its action. */
    public Menu checked(int index, boolean on) {
        Entry e = entry(index);
        if (e.type != CHECK) throw new IllegalArgumentException("entry " + index + " is not a switch");
        if (mounted()) call(Op.ITEM_CHECKED, index, on ? 1 : 0, null);
        e.checked = on;
        return this;
    }

    private Entry entry(int index) {
        if (index < 0 || index >= entries.size()) throw new IndexOutOfBoundsException("entry " + index);
        return entries.get(index);
    }

    /** Shows the menu under {@code anchor}, which must be in the menu's window. */
    public void show(Widget anchor) {
        make();
        int[] r = (int[]) anchor.call(Op.BOUNDS, null);
        call(Op.OPEN, r[0], r[3], null);
    }

    public void close() {
        if (mounted()) call(Op.CLOSE, null);
    }

    @Override
    void encode(Encoder out) {
        out.node(Encoder.MENU, this);
        int top = 0;
        for (Entry e : entries) if (e.depth == 0) top++;
        out.op(top);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            out.op(e.type);
            out.op(i);
            out.string(e.text);
            out.op(e.type == SUBMENU ? e.children : e.checked ? 1 : 0);
            out.string(e.icon);
        }
    }

    /** Applies the enabled states set before the menu was made. */
    @Override
    void made() {
        for (int i = 0; i < entries.size(); i++)
            if (!entries.get(i).enabled) call(Op.ITEM_ENABLED, i, 0, null);
    }

    @Override
    void fire(int kind, int i, double d, String s) {
        if (i < 0 || i >= entries.size()) return;
        Entry e = entries.get(i);
        if (kind == Ui.CLICK && e.click != null) e.click.run();
        else if (kind == Ui.TOGGLE && e.toggle != null) {
            e.checked = d != 0;
            e.toggle.accept(e.checked);
        }
    }
}
