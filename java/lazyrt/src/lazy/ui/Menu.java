package lazy.ui;

import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A context menu, made by {@link Window#menu} and shown under a widget with {@link #show}. Each
 * entry carries its own action; whether a switch is checked, and whether entries are enabled
 * ({@link #enabledWhen}), are asked of the app each time the menu shows, so it keeps no entry
 * numbers.
 */
public final class Menu extends Popup {
    private static final int ITEM = 0, CHECK = 1, SEPARATOR = 2, SUBMENU = 3, RADIO = 4;

    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private int depth;
    /** The condition entries added now are enabled on, or null for always. */
    private BooleanSupplier scope;

    private static final class Entry {
        final int type, depth;
        final String text, icon;
        final BooleanSupplier enabledOn;
        BooleanSupplier checkedOn;
        /** The state last shown. */
        boolean checked, enabled = true;
        Runnable click;
        Consumer<Boolean> toggle;
        int children;

        Entry(int type, int depth, String text, String icon, BooleanSupplier enabledOn) {
            this.type = type;
            this.depth = depth;
            this.text = text;
            this.icon = icon;
            this.enabledOn = enabledOn;
        }
    }

    Menu(Window window) {
        super(window);
    }

    private Entry add(int type, String text, String icon) {
        fixed();
        Entry e = new Entry(type, depth, text, icon, scope);
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

    /** Adds a switch, checked while {@code checked} says so; {@code action} gets its new state. */
    public Menu check(String text, BooleanSupplier checked, Consumer<Boolean> action) {
        Entry e = add(CHECK, Ui.nonNull(text, "text"), null);
        e.checkedOn = Ui.nonNull(checked, "checked");
        e.toggle = Ui.nonNull(action, "action");
        return this;
    }

    /**
     * Adds a choice among the radio entries next to it, checked while {@code checked} says so:
     * picking it checks it, clears the others and runs {@code action}.
     */
    public Menu radio(String text, BooleanSupplier checked, final Runnable action) {
        Ui.nonNull(action, "action");
        Entry e = add(RADIO, Ui.nonNull(text, "text"), null);
        e.checkedOn = Ui.nonNull(checked, "checked");
        e.toggle = on -> {
            if (on) action.run();
        };
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

    /**
     * Adds entries with {@code fill} that are enabled only while {@code enabled} says so, and
     * greyed out otherwise. Nested, both conditions must hold.
     */
    public Menu enabledWhen(final BooleanSupplier enabled, Consumer<Menu> fill) {
        Ui.nonNull(enabled, "enabled");
        final BooleanSupplier outer = scope;
        scope = outer == null ? enabled : () -> outer.getAsBoolean() && enabled.getAsBoolean();
        try {
            fill.accept(this);
        } finally {
            scope = outer;
        }
        return this;
    }

    /** Shows the menu under {@code anchor}, which must be in the menu's window. */
    public void show(Widget anchor) {
        boolean made = mounted();
        for (int i = 0; i < entries.size(); i++) refresh(i, made);
        make();
        int[] r = (int[]) anchor.call(Op.BOUNDS, null);
        call(Op.OPEN, r[0], r[3], null);
    }

    /** Asks the app for entry {@code i}'s state, and tells a made menu what changed. */
    private void refresh(int i, boolean made) {
        Entry e = entries.get(i);
        boolean enabled = e.enabledOn == null || e.enabledOn.getAsBoolean();
        boolean checked = e.checkedOn != null && e.checkedOn.getAsBoolean();
        if (made && enabled != e.enabled) call(Op.ITEM_ENABLED, i, enabled ? 1 : 0, null);
        // A radio is always sent: picking one clears its neighbours in xui without a message,
        // so the copy here can be stale.
        if (made && (checked != e.checked || e.type == RADIO)) call(Op.ITEM_CHECKED, i, checked ? 1 : 0, null);
        e.enabled = enabled;
        e.checked = checked;
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

    /** Applies the enabled states asked for before the menu was made. */
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
